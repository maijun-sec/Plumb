package main

// Holds the project's packages and translates files out of them.
//
// WHY THE SESSION IS PROJECT-SCOPED. A Go package's files only type-check
// TOGETHER: a file that uses a declaration from a sibling file of the same
// package does not compile on its own. So the unit of analysis is the
// package, and the unit of translation is the file -- but the file is
// translated against the package's type information, never against its own.
//
// WHY THE SESSION IS LONG-LIVED. Parsing and type-checking a package is the
// expensive part, and the Java side asks for one file at a time. Doing the
// work once per package and reusing it is what makes the sidecar approach
// usable at all.
//
// WHY THE DEPENDENCIES COME FROM EXPORT DATA. The export data is what the
// build compiled. Re-type-checking a dependency's source would mean asking
// the compiler a second time, and the second answer can differ from the
// first -- a different set of build tags, a different Go version, a
// different set of files in the directory -- and then the frontend would be
// reasoning about types the program was never compiled against. When the
// export data is missing, the dependency's source is used, and the
// difference is reported.

import (
	"bytes"
	"fmt"
	"go/ast"
	"go/build"
	"go/build/constraint"
	"go/importer"
	"go/parser"
	"go/token"
	"go/types"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// The operating systems the toolchain knows, and the architectures it
// knows. A file whose name ends with one of them is platform-specific.
var knownOperatingSystems = map[string]bool{
	"aix":       true,
	"android":   true,
	"darwin":    true,
	"dragonfly": true,
	"freebsd":   true,
	"hurd":      true,
	"illumos":   true,
	"ios":       true,
	"js":        true,
	"linux":     true,
	"nacl":      true,
	"netbsd":    true,
	"openbsd":   true,
	"plan9":     true,
	"solaris":   true,
	"wasip1":    true,
	"windows":   true,
	"zos":       true,
}

var knownArchitectures = map[string]bool{
	"386":         true,
	"amd64":       true,
	"amd64p32":    true,
	"arm":         true,
	"arm64":       true,
	"arm64be":     true,
	"armbe":       true,
	"loong64":     true,
	"mips":        true,
	"mips64":      true,
	"mips64le":    true,
	"mips64p32":   true,
	"mips64p32le": true,
	"mipsle":      true,
	"ppc":         true,
	"ppc64":       true,
	"ppc64le":     true,
	"riscv":       true,
	"riscv64":     true,
	"s390":        true,
	"s390x":       true,
	"sparc":       true,
	"sparc64":     true,
	"wasm":        true,
}

// The operating systems the "unix" build tag covers.
var unixOperatingSystems = map[string]bool{
	"aix":       true,
	"android":   true,
	"darwin":    true,
	"dragonfly": true,
	"freebsd":   true,
	"hurd":      true,
	"illumos":   true,
	"ios":       true,
	"linux":     true,
	"netbsd":    true,
	"openbsd":   true,
	"solaris":   true,
}

// A parsed source file and the path it came from.
type parsedSource struct {
	// The file's path, as the request listed it.
	path string

	// The parsed file.
	file *ast.File
}

// A type-checked package.
type loadedPackage struct {
	// The package's specification from the request.
	spec *PackageSpec

	// The type-checked package.
	pkg *types.Package

	// The type information the translator reads.
	info *types.Info

	// The parsed files, in the order they were listed.
	files []parsedSource
}

// A source file and the package it belongs to.
type sourceFile struct {
	// The file's path, as the request listed it.
	path string

	// The package the file belongs to.
	pkg *loadedPackage

	// The parsed file.
	file *ast.File
}

// The project session: one project's packages, held for the life of the
// project.
type ProjectSession struct {
	projectID string

	loader *packageLoader

	// The files, by their exact path key and by their folded path key. The
	// folded map is a FALLBACK: the Java side may spell a path with a
	// different separator or drive-letter case than the request used, and
	// answering "this file is not in the project" for a file that IS in the
	// project would look exactly like "no findings".
	byPath     map[string]*sourceFile
	byPathFold map[string]*sourceFile

	diagnostics []Diagnostic
}

// Open builds a project session from an openProject request.
func Open(request *Request) (*ProjectSession, error) {
	// The caller's copy of each file wins over the disk's copy.
	//
	// The text may be an unsaved editor buffer, and it may differ from
	// what is on disk. The analysis must see the caller's copy, because
	// the caller's copy is the code the caller believes it is analysing.
	// Reading the disk instead would be a silent inconsistency: the
	// findings would describe a version of the file the caller never saw.
	inline := map[string]string{}
	for _, source := range request.Sources {
		if source.Path == "" {
			continue
		}
		key := pathKey(source.Path)
		if _, taken := inline[key]; !taken {
			inline[key] = source.Text
		}
	}

	loader := newPackageLoader(request)
	loader.inline = inline

	session := &ProjectSession{
		projectID:  request.ProjectID,
		loader:     loader,
		byPath:     map[string]*sourceFile{},
		byPathFold: map[string]*sourceFile{},
	}

	for index := range request.Packages {
		spec := &request.Packages[index]
		loaded, err := loader.load(spec)
		if err != nil {
			// A package that cannot be loaded is a package the compiler
			// will not see. The failure is NOT fatal: the other packages
			// still work, and the diagnostics say what went wrong. What
			// must not happen is the failure disappearing, because a
			// silently missing package is a package whose findings are
			// missing.
			session.diagnostics = append(session.diagnostics, Diagnostic{
				Severity: "error",
				Message:  fmt.Sprintf("the package %q could not be loaded: %v", spec.ImportPath, err),
				Path:     spec.Dir,
				Line:     -1,
				Column:   -1,
			})
			continue
		}
		for _, source := range loaded.files {
			entry := &sourceFile{path: source.path, pkg: loaded, file: source.file}
			key := pathKey(source.path)
			session.byPath[key] = entry
			session.byPathFold[strings.ToLower(key)] = entry
		}
	}

	session.diagnostics = append(session.diagnostics, loader.diagnostics...)
	return session, nil
}

// Diagnostics returns the compiler's diagnostics for the whole project.
func (session *ProjectSession) Diagnostics() []Diagnostic {
	return session.diagnostics
}

// Translate translates one file of the project into TAC.
func (session *ProjectSession) Translate(path string) []Function {
	entry := session.lookup(path)
	if entry == nil {
		// The file is not part of the open project. The Java side asked for
		// something the sidecar does not have; saying so is better than
		// returning an empty result that reads like "no findings".
		return []Function{{
			Name:        "<unknown>",
			Signature:   "<unknown>",
			ReturnType:  Simple(TypeVoid),
			Precision:   PrecisionLowered,
			Params:      []Operand{},
			Locals:      []Operand{},
			Instructions: []Instruction{},
			Problems: []Problem{{
				Kind:    ProblemParse,
				Message: fmt.Sprintf("the file %q is not part of the open project", path),
				Line:    -1,
				Column:  -1,
			}},
			DeferredCalls: []DeferredCall{},
		}}
	}

	functions := []Function{}

	// The file's PACKAGE-LEVEL declarations. A Go package's variables are
	// initialised before the program starts, and its `init` functions run
	// before `main`. Neither has a place in the TAC's function model, so
	// without this they would be DROPPED: a package-level
	// `var secret = readSecret()` would leave no trace in the TAC, and the
	// taint that flows into it would be invisible.
	if initializer := translatePackageInit(session.loader.fset, entry.pkg, entry.file); initializer != nil {
		functions = append(functions, *initializer)
	}

	for _, decl := range entry.file.Decls {
		declaration, ok := decl.(*ast.FuncDecl)
		if !ok {
			continue
		}
		if declaration.Body == nil {
			// A declaration without a body is an assembly or linkname
			// stub. There is nothing to lower, and that is not a
			// degradation: the contract is the signature, which the caller
			// already has.
			continue
		}
		if declaration.Name.Name == "init" {
			// An `init` function is the PACKAGE's initialiser, not a
			// function of its own: Go runs it before `main`, and nobody
			// can call it. Its body is in the package initialiser above,
			// and emitting it here as well would put the same
			// instructions into the store twice.
			continue
		}
		functions = append(functions, translateFunction(session.loader.fset, entry.pkg, declaration)...)
	}
	return functions
}

// translatePackageInit lowers a file's PACKAGE-LEVEL declarations.
//
// WHAT THIS IS. The package's variables are initialised before the program
// starts, and the `init` functions run before `main`. The TAC has no
// "<clinit>" to hang them on -- it has functions, and a package-level
// variable is not one of them -- so they are collected into a function of
// their own, named after the file that declares them.
//
// WHY THE FILE. Go initialises EVERY package-level variable of a package
// before ANY `init` function runs, and the order between the files is the
// order the compiler saw them. Here each file's declarations are
// initialised in the file that declares them, so the order BETWEEN files is
// not modelled -- and the loss is reported, because an initialisation order
// that is wrong can change what a program does.
//
// A CONSTANT needs no initialisation: it is a compile-time value, and it is
// bound to the constant itself wherever it is used. A TYPE has no run-time
// effect at all.
func translatePackageInit(fset *token.FileSet, pkg *loadedPackage, file *ast.File) *Function {
	t := &translator{
		fset:             fset,
		info:             pkg.info,
		pkg:              pkg.pkg,
		instructions:     []Instruction{},
		problems:         []Problem{},
		params:           []Operand{},
		locals:           []Operand{},
		deferred:         []DeferredCall{},
		bindings:         map[types.Object]*Operand{},
		usedNames:        map[string]int{},
		labelled:         map[string]*breakContext{},
		closureByVar:     map[types.Object]*closure{},
		closureByLiteral: map[*ast.FuncLit]*closure{},
	}

	owner := pkg.pkg.Path()
	t.owner = owner
	t.enclosing = owner

	hasVariables := false
	for _, decl := range file.Decls {
		general, ok := decl.(*ast.GenDecl)
		if !ok || general.Tok != token.VAR {
			continue
		}
		for _, spec := range general.Specs {
			value, ok := spec.(*ast.ValueSpec)
			if !ok {
				continue
			}
			for index, name := range value.Names {
				if name.Name == "_" {
					continue
				}
				variable, ok := t.info.Defs[name].(*types.Var)
				if !ok {
					continue
				}
				hasVariables = true
				// The package-level variable is bound to its QUALIFIED name,
				// so that the functions of every file of the package read
				// and write the SAME TAC variable.
				operand := t.binding(variable)
				t.emitPackageVariable(value, index, operand)
			}
		}
	}

	hasInit := false
	for _, decl := range file.Decls {
		declaration, ok := decl.(*ast.FuncDecl)
		if !ok || declaration.Body == nil {
			continue
		}
		if declaration.Name.Name != "init" {
			continue
		}
		hasInit = true
		t.translateBlock(declaration.Body)
	}

	if !hasVariables && !hasInit {
		return nil
	}

	precision := PrecisionFull
	if t.degraded {
		precision = PrecisionLowered
	}
	if hasVariables {
		t.report(file, ProblemSourceLowered,
			"a package's variables are initialised before its 'init' functions run, and the order "+
				"between the files of a package is not modelled: each file's declarations are "+
				"initialised in the file that declares them")
	}

	name := packageInitName(fset, file)
	return &Function{
		Name:          name,
		Signature:     declarationSignature(owner, name, nil, ""),
		EnclosingClass: owner,
		ReturnType:    Simple(TypeVoid),
		Precision:     precision,
		Synthetic:     false,
		Params:        []Operand{},
		Locals:        t.locals,
		Instructions:  t.instructions,
		Problems:      t.problems,
		DeferredCalls: t.deferred,
		StartsGoroutine: t.startsGoroutine,
	}
}

// emitPackageVariable initialises one package-level variable.
//
// The value of a multi-name declaration (`var a, b = f()`) is a
// multi-valued expression: the first name takes the call's first result and
// the rest are unknown, because the TAC's CALL has one destination.
func (t *translator) emitPackageVariable(spec *ast.ValueSpec, index int, operand *Operand) {
	if len(spec.Values) == 0 {
		return
	}
	if index < len(spec.Values) {
		value := t.valueOf(spec.Values[index])
		t.emit(Instruction{Kind: KindMove, Dest: operand, Lhs1: value}, spec.Pos())
		return
	}
	if len(spec.Values) == 1 && index == 1 {
		if call, ok := spec.Values[0].(*ast.CallExpr); ok {
			t.translateCall(call, operand)
			t.report(spec, ProblemSourceLowered,
				"a result beyond the first could not be bound: the TAC's CALL has one destination")
			return
		}
	}
	t.emit(Instruction{Kind: KindHavoc, Dest: operand}, spec.Pos())
	t.report(spec, ProblemSourceLowered,
		"a result beyond the first could not be bound: the TAC's CALL has one destination")
}

// packageInitName names a file's package initialiser.
//
// The name carries the FILE's name, because a package's initialisation is
// spread over its files and the TAC's store indexes a function under
// "enclosing scope :: signature": two files' initialisers with the same name
// would be one function as far as the call graph is concerned.
func packageInitName(fset *token.FileSet, file *ast.File) string {
	path := fset.Position(file.Pos()).Filename
	base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	if base == "" {
		base = "package"
	}
	return base + ".init"
}

// lookup finds a file by path.
func (session *ProjectSession) lookup(path string) *sourceFile {
	if entry, ok := session.byPath[pathKey(path)]; ok {
		return entry
	}
	if entry, ok := session.byPathFold[strings.ToLower(pathKey(path))]; ok {
		return entry
	}
	return nil
}

// pathKey normalises a path for lookup.
//
// The separator is normalised because the Java side may send a Windows path
// while the sidecar runs on a Unix host (or the other way round), and a path
// that differs only in its separators names the same file.
func pathKey(path string) string {
	if path == "" {
		return ""
	}
	return filepath.ToSlash(filepath.Clean(path))
}

// The package loader: parses the project's packages, type-checks them, and
// resolves their imports.
type packageLoader struct {
	fset *token.FileSet

	// The project's packages, by import path.
	specs map[string]*PackageSpec

	// The export data, by import path.
	exportData map[string]string

	// The source directories, by import path.
	sourceDirs map[string]string

	// The modules that live on disk, by module path.
	//
	// A module the Java side named: a REPLACED dependency, or one that
	// belongs to another module of a workspace. The module cache holds
	// neither, so this is the only way to find their source.
	modules map[string]string

	// The type-checked packages, by import path.
	packages map[string]*types.Package

	// The type information, by import path.
	infos map[string]*types.Info

	// The parsed files, by import path.
	parsed map[string][]parsedSource

	// The packages currently being checked, for the import-cycle guard.
	loading map[string]bool

	// The caller's copy of the files, by path key. A file that appears
	// here is parsed and type-checked from the caller's text, not from
	// the disk.
	inline map[string]string

	// The directory the project's own packages live in.
	//
	// It is the anchor for resolving a dependency that has no export data:
	// the resolution is relative to the project, because that is where the
	// project's module is.
	projectRoot string

	// The importer that reads the export data.
	gcImporter types.Importer

	// The Go toolchain's root directory, or "" when none was found.
	//
	// It is where the standard library's source lives, and the standard
	// library is where most of a Go program's dependencies are.
	goroot string

	// The build context that locates a dependency's source.
	//
	// It is a COPY of build.Default with the GOROOT replaced, because
	// build.Default takes its GOROOT from the environment -- and the
	// environment's GOROOT is a leftover that may name a toolchain which no
	// longer exists.
	buildContext build.Context

	// The module cache's directory, or "" when there is none.
	//
	// It is where a third-party dependency's source lives. go/build does not
	// know about it: the cache is keyed by module path and version, while
	// the import path names a package.
	moduleCache string

	// Why the toolchain's root could not be used, or "" when it could.
	gorootProblem string

	// Whether the toolchain problem has already been reported.
	gorootProblemReported bool

	diagnostics []Diagnostic
}

// newPackageLoader builds a loader from an openProject request.
func newPackageLoader(request *Request) *packageLoader {
	loader := &packageLoader{
		fset:       token.NewFileSet(),
		specs:      map[string]*PackageSpec{},
		exportData: map[string]string{},
		sourceDirs: map[string]string{},
		modules:    map[string]string{},
		packages:   map[string]*types.Package{},
		infos:      map[string]*types.Info{},
		parsed:     map[string][]parsedSource{},
		loading:    map[string]bool{},
	}

	for index := range request.Modules {
		module := &request.Modules[index]
		if module.Path == "" || module.Dir == "" {
			continue
		}
		if _, taken := loader.modules[module.Path]; !taken {
			loader.modules[module.Path] = module.Dir
		}
	}

	for index := range request.Packages {
		spec := &request.Packages[index]
		loader.specs[spec.ImportPath] = spec
		if loader.projectRoot == "" && spec.Dir != "" {
			loader.projectRoot = spec.Dir
		}
		for _, imported := range spec.Imports {
			if imported.ExportData != "" {
				if _, taken := loader.exportData[imported.ImportPath]; !taken {
					loader.exportData[imported.ImportPath] = imported.ExportData
				}
			}
			if imported.Dir != "" {
				if _, taken := loader.sourceDirs[imported.ImportPath]; !taken {
					loader.sourceDirs[imported.ImportPath] = imported.Dir
				}
			}
		}
	}

	// The gc importer reads the export data. The lookup is what makes the
	// import paths resolve to the files the Java side named, instead of to
	// whatever happens to be in $GOPATH.
	lookup := func(path string) (io.ReadCloser, error) {
		file, ok := loader.exportData[path]
		if !ok {
			return nil, fmt.Errorf("no export data was supplied for the import path %q", path)
		}
		return os.Open(file)
	}
	loader.gcImporter = importer.ForCompiler(loader.fset, "gc", lookup)

	loader.resolveToolchain(request.Goroot)
	loader.moduleCache = moduleCacheDirectory()

	return loader
}

// resolveToolchain decides which toolchain's root to use.
//
// The order is deliberate:
//
//  1. the root the caller NAMED -- it is the toolchain that built the
//     project, so it is the toolchain whose standard library the project was
//     compiled against;
//  2. the environment's GOROOT, but only when it names a directory that
//     exists. A stale GOROOT is worse than no GOROOT: it makes the lookup
//     fail silently, because a directory that is not there looks exactly like
//     a standard library that is not there;
//  3. the go command on the PATH, which lives in <root>/bin.
//
// When none of them works, the problem is recorded. A dependency whose
// source cannot be found is a dependency whose types are missing, and a
// missing type is a missing finding -- so the reason has to reach the user
// instead of disappearing into the type checker's error stream.
func (loader *packageLoader) resolveToolchain(named string) {
	// 1. The root the caller NAMED: the toolchain that built the project,
	//    and therefore the toolchain whose standard library the project was
	//    compiled against.
	if named != "" && isToolchainRoot(named) {
		loader.goroot = named
	}
	// 2. The environment's GOROOT, but only when it names a toolchain that
	//    exists. A stale GOROOT is worse than no GOROOT: it makes the lookup
	//    fail silently, because a directory that is not there looks exactly
	//    like a standard library that is not there.
	if loader.goroot == "" {
		if fromEnv := os.Getenv("GOROOT"); fromEnv != "" && isToolchainRoot(fromEnv) {
			loader.goroot = fromEnv
		}
	}
	// 3. The go command on the PATH, which lives in <root>/bin.
	if loader.goroot == "" {
		if found := toolchainRootOnPath(); found != "" {
			loader.goroot = found
		}
	}

	// The problem is reported only when NOTHING worked. A stale GOROOT that
	// was superseded by a working toolchain is not a problem -- and saying
	// it was would be worse than silence, because it would say the standard
	// library could not be located when it could.
	if loader.goroot == "" {
		loader.gorootProblem = toolchainProblem(named)
	}

	context := build.Default
	if loader.goroot != "" {
		context.GOROOT = loader.goroot
	} else {
		// The environment's GOROOT is not usable, so the context must not
		// carry it: leaving it there would send the lookup into a directory
		// that does not exist and report "no such package" for a package
		// that is present.
		context.GOROOT = ""
	}
	loader.buildContext = context

	// The problem is NOT reported here. It is only a problem when an import
	// actually failed because of it: a project whose dependencies came from
	// their export data never needed the standard library's source, and
	// saying "every import of the standard library stays unresolved" would
	// then be a lie. The diagnostic is emitted at the point of failure.
}

// reportGorootProblem records the toolchain problem, once.
//
// The message says that the standard library's source could not be located.
// That is true only when an import actually needed it, which is why the
// diagnostic is raised here and not when the toolchain was resolved.
func (loader *packageLoader) reportGorootProblem() {
	if loader.gorootProblem == "" || loader.gorootProblemReported {
		return
	}
	loader.gorootProblemReported = true
	loader.diagnostics = append(loader.diagnostics, Diagnostic{
		Severity: "error",
		Message: fmt.Sprintf(
			"%s, so the standard library's source could not be located: every import of the "+
				"standard library that had no export data stays unresolved, and the calls into it "+
				"carry no types",
			loader.gorootProblem),
		Line:   -1,
		Column: -1,
	})
}

// toolchainProblem says why no toolchain root could be used.
//
// It names what was tried, because a reader who has to fix it needs to know
// which of the three answers was wrong.
func toolchainProblem(named string) string {
	if named != "" {
		return fmt.Sprintf(
			"the Go toolchain the build used is at %q, which is not a toolchain on this machine",
			named)
	}
	if fromEnv := os.Getenv("GOROOT"); fromEnv != "" {
		return fmt.Sprintf(
			"the GOROOT in the environment is %q, which is not a toolchain on this machine, and no "+
				"go command was found on the PATH", fromEnv)
	}
	return "no Go toolchain root could be found: the go command is not on the PATH"
}

// toolchainRootOnPath finds the Go toolchain's root through the go command on
// the PATH.
//
// The go command lives at <root>/bin/go, so the root is two levels up.
//
// WHY NOT THE TOOLCHAIN THIS SIDECAR WAS BUILT WITH. runtime.GOROOT() would
// answer that, but it reads the environment the RUNTIME snapshotted when the
// process started -- unsetting the variable afterwards changes nothing,
// because the snapshot is already taken. So the only reliable way to learn
// the build-time root would be to read the runtime's private state, and a
// private state that cannot be read is not an answer.
func toolchainRootOnPath() string {
	found, err := exec.LookPath("go")
	if err != nil || found == "" {
		return ""
	}
	bin := filepath.Dir(found)
	root := filepath.Dir(bin)
	if !isToolchainRoot(root) {
		return ""
	}
	return root
}

// isToolchainRoot reports whether a path names a Go toolchain's root.
//
// The check is for the standard library's SOURCE, and not merely for a
// directory. A directory can outlive the toolchain that filled it: an emptied
// GOROOT that still holds the module cache looks exactly like a toolchain to a
// directory check, and the lookup that follows then finds no source at all --
// which is the silent failure this whole mechanism exists to prevent.
func isToolchainRoot(path string) bool {
	if path == "" || !isDirectory(path) {
		return false
	}
	return isDirectory(filepath.Join(path, "src"))
}

// isDirectory reports whether a path names a directory.
func isDirectory(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// Import resolves an import path.
//
// It is the types.Importer the type checker calls, so it is the one place
// where "where does this dependency come from" is decided:
//
//   - a package of the project is type-checked from its own files;
//   - a dependency with export data is read from the export data;
//   - a dependency with only a source directory is type-checked from that
//     directory's source.
//
// The order matters: the export data is what the build compiled, so it wins.
//
// The export data WINS only when it can be read. A capture that outlived its
// build names export data files that the build deleted with its temporary
// directory, and a dependency that is then given up on is a dependency whose
// calls resolve to nothing -- a missing finding, not a missing precision. So
// an export data that cannot be read falls back to the source directory.
func (loader *packageLoader) Import(path string) (*types.Package, error) {
	if pkg, ok := loader.packages[path]; ok && pkg.Complete() {
		return pkg, nil
	}
	if loader.loading[path] {
		return nil, fmt.Errorf("the import path %q is part of an import cycle", path)
	}

	if spec, ok := loader.specs[path]; ok {
		loaded, err := loader.load(spec)
		if err != nil {
			return nil, err
		}
		return loaded.pkg, nil
	}
	if _, ok := loader.exportData[path]; ok {
		pkg, err := loader.importFromExportData(path)
		if err == nil {
			return pkg, nil
		}
		// The export data is NAMED but cannot be read.
		//
		// The export data lives in the build's temporary directory, and the
		// build deletes that directory when it ends -- so a capture that
		// outlived its build names files that are gone. Failing here would
		// leave the dependency unresolved, and an unresolved dependency is a
		// missing finding rather than a missing precision. The source is a
		// second opinion about the dependency's types, and a second opinion
		// beats no answer at all.
		if dir, ok := loader.sourceDirs[path]; ok {
			return loader.importFromSource(path, dir)
		}
		return nil, err
	}
	if dir, ok := loader.sourceDirs[path]; ok {
		return loader.importFromSource(path, dir)
	}
	// The last resort: find the dependency's source and type-check it from
	// there.
	//
	// This is not a fallback of convenience -- it is the case the capture
	// ALWAYS produces. The export data lives in the build's temporary
	// directory, and the build deletes that directory when it ends, so by
	// the time the analysis reads the capture the export data is gone. The
	// standard library's source is under GOROOT and a module dependency's
	// source is in the module cache, and go/build knows where both are.
	if dir := loader.sourceDirectoryOf(path); dir != "" {
		return loader.importFromSource(path, dir)
	}
	if loader.gorootProblem != "" {
		loader.reportGorootProblem()
		return nil, fmt.Errorf(
			"could not import %q: %s, so its source could not be found", path, loader.gorootProblem)
	}
	if loader.moduleCache == "" {
		return nil, fmt.Errorf(
			"could not import %q: it has no export data, and the module cache was not found, "+
				"so its source could not be read either", path)
	}
	return nil, fmt.Errorf("no export data and no source directory were supplied for the import path %q", path)
}

// sourceDirectoryOf finds a dependency's source directory.
//
// The lookup is relative to the project, because that is where the project's
// module is: a dependency of the project is either in the standard library,
// which go/build finds under GOROOT, or in the module cache, which go/build
// finds by asking the toolchain.
func (loader *packageLoader) sourceDirectoryOf(path string) string {
	if path == "" {
		return ""
	}
	// A module the Java side named comes FIRST.
	//
	// A REPLACED dependency (`replace other.example/mod => ../repo`) and a
	// dependency that belongs to another module of a WORKSPACE are
	// directories on disk, not downloads, so the module cache does not hold
	// them and go/build does not know about them. The Java side read the
	// module's own build description, which is the only place the
	// replacement is written down, and named the directory here.
	if dir := loader.localModuleDirectory(path); dir != "" {
		return dir
	}
	context := loader.buildContext
	anchor := loader.projectRoot
	if anchor == "" {
		anchor = "."
	}
	package_, err := context.Import(path, anchor, build.FindOnly)
	if err == nil && package_ != nil {
		return package_.Dir
	}
	// A third-party dependency is neither under the toolchain's root nor in
	// GOPATH: it is in the module cache, under a path go/build does not
	// know. The standard library was found above, and the module cache is
	// only consulted for what is left.
	if dir := moduleDirectoryOf(loader.moduleCache, path); dir != "" {
		return dir
	}
	return ""
}

// localModuleDirectory finds a dependency's source directory in the modules the
// Java side named.
//
// The import path may name a package INSIDE a module, so every module path is
// a candidate prefix -- and the LONGEST one wins, so a module named
// "example.com/m" does not swallow the import "example.com/m2/pkg".
func (loader *packageLoader) localModuleDirectory(path string) string {
	if path == "" || len(loader.modules) == 0 {
		return ""
	}
	best := ""
	for module := range loader.modules {
		if module == "" {
			continue
		}
		if path != module && !strings.HasPrefix(path, module+"/") {
			continue
		}
		if len(module) > len(best) {
			best = module
		}
	}
	if best == "" {
		return ""
	}
	directory := loader.modules[best]
	if directory == "" {
		return ""
	}
	if path != best {
		directory = filepath.Join(directory, filepath.FromSlash(path[len(best)+1:]))
	}
	if !isDirectory(directory) {
		return ""
	}
	return directory
}

// sourceFilesOf lists the Go files of a source directory.
//
// The build constraints decide which of them are part of the package, so the
// list is the one the toolchain would compile -- on this platform, with
// these tags.
func (loader *packageLoader) sourceFilesOf(dir string) []string {
	if dir == "" {
		return nil
	}
	context := loader.buildContext
	package_, err := context.ImportDir(dir, 0)
	if err != nil || package_ == nil {
		return nil
	}
	files := []string{}
	for _, name := range package_.GoFiles {
		files = append(files, filepath.Join(dir, name))
	}
	for _, name := range package_.CgoFiles {
		files = append(files, filepath.Join(dir, name))
	}
	return files
}

// load parses and type-checks one package of the project.
func (loader *packageLoader) load(spec *PackageSpec) (*loadedPackage, error) {
	if pkg, ok := loader.packages[spec.ImportPath]; ok && pkg.Complete() {
		return &loadedPackage{
			spec:  spec,
			pkg:   pkg,
			info:  loader.infos[spec.ImportPath],
			files: loader.parsed[spec.ImportPath],
		}, nil
	}

	sources, err := loader.parsePackage(spec)
	if err != nil {
		return nil, err
	}
	if len(sources) == 0 {
		return nil, fmt.Errorf("the package has no files that the build tags select")
	}

	files := make([]*ast.File, 0, len(sources))
	for _, source := range sources {
		files = append(files, source.file)
	}

	info := newTypeInfo()
	loader.loading[spec.ImportPath] = true
	defer delete(loader.loading, spec.ImportPath)

	configuration := &types.Config{
		Importer: loader,
		// The type checker keeps going after an error and reports every one
		// of them. Stopping at the first would hide the rest of the
		// project's problems behind one bad file.
		Error: func(err error) {
			loader.recordTypeError(spec.ImportPath, err)
		},
		// A file that imports a package it does not use is legal Go and
		// common in generated code. It is not a problem the analysis cares
		// about, and reporting it would bury the real diagnostics.
		DisableUnusedImportCheck: true,
	}
	if spec.GoVersion != "" {
		configuration.GoVersion = spec.GoVersion
	}

	pkg, _ := configuration.Check(spec.ImportPath, loader.fset, files, info)
	if pkg == nil {
		return nil, fmt.Errorf("the package did not type-check at all")
	}

	loader.packages[spec.ImportPath] = pkg
	loader.infos[spec.ImportPath] = info
	loader.parsed[spec.ImportPath] = sources

	return &loadedPackage{spec: spec, pkg: pkg, info: info, files: sources}, nil
}

// importFromExportData reads a dependency from its export data.
func (loader *packageLoader) importFromExportData(path string) (*types.Package, error) {
	pkg, err := loader.gcImporter.Import(path)
	if err != nil {
		return nil, fmt.Errorf("could not read the export data for %q: %w", path, err)
	}
	if pkg == nil {
		return nil, fmt.Errorf("the export data for %q named no package", path)
	}

	// Pull in the dependency's own dependencies.
	//
	// The export data names the packages it refers to, but it does not carry
	// their contents. A package that is only NAMED is a placeholder: the
	// types that mention it resolve to an empty package, and every field
	// that uses one of its types comes out invalid. So the closure is
	// walked here, while the export data for the dependencies is still
	// available.
	for _, imported := range pkg.Imports() {
		if imported.Complete() {
			continue
		}
		if _, ok := loader.exportData[imported.Path()]; !ok {
			continue
		}
		if _, err := loader.importFromExportData(imported.Path()); err != nil {
			loader.diagnostics = append(loader.diagnostics, Diagnostic{
				Severity: "warning",
				Message: fmt.Sprintf(
					"the dependency %q of %q could not be read: %v",
					imported.Path(), path, err),
				Line:   -1,
				Column: -1,
			})
		}
	}

	loader.packages[path] = pkg
	return pkg, nil
}

// importFromSource type-checks a dependency from its source directory.
//
// This is the FALLBACK. The export data is preferred because it is what the
// build compiled; a source directory is used only when there is no export
// data, and the result is a second opinion about the dependency's types
// rather than the build's own.
//
// The files are the ones the build constraints select, so the dependency is
// the same package the program links against -- on the platform, and with
// the tags, that the analysis runs on.
func (loader *packageLoader) importFromSource(path string, dir string) (*types.Package, error) {
	spec := &PackageSpec{ImportPath: path, Dir: dir}
	spec.Files = loader.sourceFilesOf(dir)
	sources, err := loader.parsePackage(spec)
	if err != nil {
		return nil, err
	}
	if len(sources) == 0 {
		return nil, fmt.Errorf("the source directory %q has no files", dir)
	}

	files := make([]*ast.File, 0, len(sources))
	for _, source := range sources {
		files = append(files, source.file)
	}

	info := newTypeInfo()
	configuration := &types.Config{
		Importer: loader,
		Error: func(err error) {
			loader.recordTypeError(path, err)
		},
		DisableUnusedImportCheck: true,
	}
	pkg, _ := configuration.Check(path, loader.fset, files, info)
	if pkg == nil {
		return nil, fmt.Errorf("the dependency %q did not type-check at all", path)
	}
	loader.packages[path] = pkg
	loader.infos[path] = info
	loader.parsed[path] = sources
	return pkg, nil
}

// parsePackage parses the files of a package, honouring the build tags.
//
// The build tags decide which files the BUILD compiles. A file the build
// skips is a file the program does not contain, so translating it would
// report findings in code that does not exist in the binary.
//
// A file the caller sent its own text for is judged by THAT text: the
// constraint may have been added since the file was last written, and the
// disk's copy is not the code under analysis.
func (loader *packageLoader) parsePackage(spec *PackageSpec) ([]parsedSource, error) {
	context := buildContext(spec)

	sources := []parsedSource{}
	for _, path := range spec.Files {
		if path == "" {
			continue
		}

		var source []byte
		text, inline := loader.inline[pathKey(path)]
		if inline {
			source = []byte(text)
			if !loader.inlineSelected(path, source, context) {
				continue
			}
		} else {
			directory := filepath.Dir(path)
			name := filepath.Base(path)
			matched, err := context.MatchFile(directory, name)
			if err != nil {
				loader.diagnostics = append(loader.diagnostics, Diagnostic{
					Severity: "warning",
					Message:  fmt.Sprintf("the build constraints of %q could not be read: %v", path, err),
					Path:     path,
					Line:     -1,
					Column:   -1,
				})
				continue
			}
			if !matched {
				continue
			}
			// The file is read HERE rather than left to the parser.
			//
			// The parser's source parameter is an `any`, and a nil []byte
			// inside an `any` is NOT a nil interface: the parser reads it as
			// "the source is empty" rather than "the source is the file named
			// by the path". Passing the nil slice straight through therefore
			// parses nothing at all, and every file comes out empty.
			content, err := os.ReadFile(path)
			if err != nil {
				loader.diagnostics = append(loader.diagnostics, Diagnostic{
					Severity: "error",
					Message:  fmt.Sprintf("the file %q could not be read: %v", path, err),
					Path:     path,
					Line:     -1,
					Column:   -1,
				})
				continue
			}
			source = content
		}

		parsed, err := parser.ParseFile(loader.fset, path, source,
			parser.ParseComments|parser.SkipObjectResolution)
		if err != nil {
			loader.diagnostics = append(loader.diagnostics, Diagnostic{
				Severity: "error",
				Message:  fmt.Sprintf("the file %q could not be parsed: %v", path, err),
				Path:     path,
				Line:     -1,
				Column:   -1,
			})
			continue
		}
		sources = append(sources, parsedSource{path: path, file: parsed})
	}
	return sources, nil
}

// inlineSelected reports whether the build selects a file the caller sent
// its own text for.
//
// The file's own header decides, because the disk's copy is not the code
// under analysis. A file whose constraint cannot be parsed is KEPT: dropping
// it would lose the file silently, and a file that does not compile is
// reported by the type checker rather than by the build-tag filter.
func (loader *packageLoader) inlineSelected(path string, source []byte, context *build.Context) bool {
	if !nameMatchesPlatform(filepath.Base(path), context) {
		return false
	}
	expression, ok := buildConstraint(source)
	if !ok {
		return true
	}
	return expression.Eval(func(tag string) bool {
		return tagMatches(tag, context)
	})
}

// buildConstraint finds the build constraint in a file's header.
//
// The header is the run of comment lines before the package clause: that is
// where the build constraints live. A constraint line after the package
// clause is not a constraint.
func buildConstraint(source []byte) (constraint.Expr, bool) {
	var result constraint.Expr

	offset := 0
	for offset < len(source) {
		end := bytes.IndexByte(source[offset:], '\n')
		var line []byte
		if end < 0 {
			line = source[offset:]
			offset = len(source)
		} else {
			line = source[offset : offset+end]
			offset = offset + end + 1
		}

		trimmed := bytes.TrimSpace(line)
		if len(trimmed) == 0 {
			continue
		}
		if !bytes.HasPrefix(trimmed, []byte("//")) {
			// The header ended: this is the package clause.
			break
		}

		parsed, err := constraint.Parse(string(trimmed))
		if err != nil {
			continue
		}
		if result == nil {
			result = parsed
		} else {
			// Several constraint lines are AND-ed together.
			result = &constraint.AndExpr{X: result, Y: parsed}
		}
	}

	return result, result != nil
}

// nameMatchesPlatform reports whether a file's NAME selects the target.
//
// The rule is the toolchain's: a file named `foo_linux.go` is built only on
// Linux, and a file named `foo_bar.go` where "bar" is not an operating
// system or an architecture is built everywhere. Getting this wrong would
// include a file the build excludes -- or exclude one it includes.
func nameMatchesPlatform(name string, context *build.Context) bool {
	name, _, _ = strings.Cut(name, ".")

	// Only the part after the FIRST underscore carries a platform suffix:
	// "my_util.go" is not a platform-specific file.
	index := strings.Index(name, "_")
	if index < 0 {
		return true
	}
	parts := strings.Split(name[index:], "_")
	if count := len(parts); count > 0 && parts[count-1] == "test" {
		parts = parts[:count-1]
	}

	count := len(parts)
	if count >= 2 && knownOperatingSystems[parts[count-2]] && knownArchitectures[parts[count-1]] {
		return tagMatches(parts[count-1], context) && tagMatches(parts[count-2], context)
	}
	if count >= 1 && (knownOperatingSystems[parts[count-1]] || knownArchitectures[parts[count-1]]) {
		return tagMatches(parts[count-1], context)
	}
	return true
}

// tagMatches reports whether a build tag is satisfied.
func tagMatches(tag string, context *build.Context) bool {
	switch tag {
	case context.GOOS, context.GOARCH:
		return true
	case "cgo":
		return context.CgoEnabled
	case "gc":
		return context.Compiler == "gc"
	case "unix":
		return unixOperatingSystems[context.GOOS]
	}
	for _, candidate := range context.BuildTags {
		if candidate == tag {
			return true
		}
	}
	for _, candidate := range context.ToolTags {
		if candidate == tag {
			return true
		}
	}
	for _, candidate := range context.ReleaseTags {
		if candidate == tag {
			return true
		}
	}
	return false
}

// recordTypeError records a type-checking error as a diagnostic.
//
// A type error in the PROJECT's own code is an error: the code does not
// compile, and the caller has to know.
//
// A type error in a DEPENDENCY is not the project's error. The dependency was
// resolved from its source because the build's export data was gone, and the
// source of a dependency may not type-check under a type checker alone -- the
// standard library's `unsafe` conversions are the standing example, because
// the compiler gives them meaning that no type checker reproduces.
//
// So a dependency's type error is recorded as information, not as an error.
// Reporting it as an error would say the project does not compile when the
// project is fine and the dependency's source simply cannot be checked the
// way the compiler checks it.
func (loader *packageLoader) recordTypeError(path string, err error) {
	severity := "error"
	if _, isProject := loader.specs[path]; !isProject {
		severity = "info"
	}
	typeError, ok := err.(types.Error)
	if !ok {
		loader.diagnostics = append(loader.diagnostics, Diagnostic{
			Severity: severity,
			Message:  err.Error(),
			Line:     -1,
			Column:   -1,
		})
		return
	}
	diagnostic := Diagnostic{
		Severity: severity,
		Message:  typeError.Msg,
		Line:     -1,
		Column:   -1,
	}
	if position := loader.fset.Position(typeError.Pos); position.IsValid() {
		diagnostic.Path = position.Filename
		diagnostic.Line = position.Line
		diagnostic.Column = position.Column
	}
	loader.diagnostics = append(loader.diagnostics, diagnostic)
}

// newTypeInfo builds the type information the translator reads.
func newTypeInfo() *types.Info {
	return &types.Info{
		Types:      map[ast.Expr]types.TypeAndValue{},
		Defs:       map[*ast.Ident]types.Object{},
		Uses:       map[*ast.Ident]types.Object{},
		Selections: map[*ast.SelectorExpr]*types.Selection{},
		Scopes:     map[ast.Node]*types.Scope{},
		Implicits:  map[ast.Node]types.Object{},
	}
}

// buildContext builds the build context for a package.
//
// The request's build tags are the tags the build ran with. A tag that names
// an operating system or an architecture becomes the context's GOOS or
// GOARCH, because that is how the build constraints are written; the rest
// become ordinary build tags.
//
// The release tags are cut at the package's own Go version, so that a
// `//go:build go1.22` constraint is judged the way the BUILD judged it and
// not the way this toolchain would judge it.
func buildContext(spec *PackageSpec) *build.Context {
	context := build.Default
	context.BuildTags = nil
	context.CgoEnabled = true
	context.ReleaseTags = releaseTagsFor(spec.GoVersion)

	for _, tag := range spec.BuildTags {
		switch {
		case knownOperatingSystems[tag]:
			context.GOOS = tag
		case knownArchitectures[tag]:
			context.GOARCH = tag
		default:
			context.BuildTags = append(context.BuildTags, tag)
		}
	}
	return &context
}

// releaseTagsFor returns the release tags up to a Go version.
//
// The toolchain's own release tags run to the toolchain's version, which
// may be NEWER than the version the project was built with. A constraint on
// a language version the project never had must not select a file.
func releaseTagsFor(version string) []string {
	if version == "" {
		return build.Default.ReleaseTags
	}
	major, minor, ok := parseGoVersion(version)
	if !ok {
		return build.Default.ReleaseTags
	}
	tags := []string{}
	for _, tag := range build.Default.ReleaseTags {
		tagMajor, tagMinor, ok := parseGoVersion(tag)
		if !ok {
			continue
		}
		if tagMajor < major || (tagMajor == major && tagMinor <= minor) {
			tags = append(tags, tag)
		}
	}
	return tags
}

// parseGoVersion parses a "go1.21" style version into its numbers.
func parseGoVersion(version string) (int, int, bool) {
	text := strings.TrimPrefix(strings.TrimSpace(version), "go")
	if text == "" {
		return 0, 0, false
	}
	parts := strings.Split(text, ".")
	major, err := strconv.Atoi(parts[0])
	if err != nil {
		return 0, 0, false
	}
	minor := 0
	if len(parts) > 1 {
		minor, err = strconv.Atoi(parts[1])
		if err != nil {
			return 0, 0, false
		}
	}
	return major, minor, true
}
