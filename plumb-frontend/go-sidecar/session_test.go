package main

// Tests for the toolchain resolution: where the standard library's source is
// found, and what happens when the answer cannot be trusted.
//
// WHY THESE TESTS EXIST. The standard library is found under the Go
// toolchain's root, and the root used to come from the environment alone. The
// environment's GOROOT outlives the toolchain it names -- a developer who
// removes a toolchain keeps the variable -- and a GOROOT that points at a
// directory which is not there makes every standard library import fail
// SILENTLY: the imports go unresolved, the calls into the standard library
// lose their types, and the findings that depend on them disappear while the
// scan reports success.

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// testToolchainRoot returns a toolchain root that names a real directory.
//
// The go command sets GOROOT for the test process, so the environment's
// value is a real toolchain here -- which is exactly what the test needs.
func testToolchainRoot(t *testing.T) string {
	t.Helper()

	root := os.Getenv("GOROOT")
	if root == "" || !isDirectory(root) {
		t.Skip("this test needs a GOROOT that names a directory")
	}
	return root
}

func TestResolveToolchainUsesTheNamedToolchain(t *testing.T) {
	root := testToolchainRoot(t)

	loader := &packageLoader{}
	loader.resolveToolchain(root)

	if loader.goroot != root {
		t.Errorf("the toolchain is %q, want the named one %q", loader.goroot, root)
	}
	if loader.gorootProblem != "" {
		t.Errorf("a usable toolchain reported a problem: %s", loader.gorootProblem)
	}
	if !isDirectory(loader.buildContext.GOROOT) {
		t.Errorf("the build context's GOROOT is %q, which is not a directory", loader.buildContext.GOROOT)
	}
}

func TestResolveToolchainRefusesANamedDirectoryThatIsNotThere(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "no-such-toolchain")
	t.Setenv("GOROOT", missing)
	// The PATH is emptied so that the go command cannot rescue the lookup:
	// this test is about the NAMED root, not about the fallbacks.
	t.Setenv("PATH", t.TempDir())

	loader := &packageLoader{}
	loader.resolveToolchain(missing)

	if loader.goroot == missing {
		t.Error("a toolchain root that does not exist was used")
	}
	if loader.gorootProblem == "" {
		t.Error("a toolchain root that does not exist was accepted without a word")
	}
	if loader.goroot != "" && !isDirectory(loader.goroot) {
		t.Errorf("the toolchain %q is not a directory", loader.goroot)
	}
}

func TestResolveToolchainIgnoresAStaleEnvironmentGoroot(t *testing.T) {
	stale := filepath.Join(t.TempDir(), "removed-toolchain")
	t.Setenv("GOROOT", stale)
	t.Setenv("PATH", t.TempDir())

	loader := &packageLoader{}
	loader.resolveToolchain("")

	if loader.goroot == stale {
		t.Error("the stale GOROOT in the environment was used")
	}
	if loader.buildContext.GOROOT == stale {
		t.Error("the stale GOROOT in the environment reached the build context")
	}
	if loader.goroot != "" && !isDirectory(loader.goroot) {
		t.Errorf("the toolchain %q is not a directory", loader.goroot)
	}
}

func TestSourceDirectoryOfFindsTheStandardLibrary(t *testing.T) {
	root := testToolchainRoot(t)

	loader := &packageLoader{}
	loader.resolveToolchain(root)

	dir := loader.sourceDirectoryOf("fmt")
	if dir == "" {
		t.Fatal("the standard library's source was not found")
	}
	if _, err := os.Stat(filepath.Join(dir, "print.go")); err != nil {
		t.Errorf("the directory %q does not hold the standard library's source: %v", dir, err)
	}
}

func TestImportSaysWhyADependencyCouldNotBeFound(t *testing.T) {
	loader := &packageLoader{}
	loader.buildContext.GOROOT = ""
	loader.gorootProblem = "no Go toolchain root could be found"

	_, err := loader.Import("fmt")
	if err == nil {
		t.Fatal("the import succeeded without a toolchain")
	}
	if !strings.Contains(err.Error(), "no Go toolchain root could be found") {
		t.Errorf("the failure did not say why: %v", err)
	}
}

func TestSourceDirectoryOfFailsWithoutAUsableToolchain(t *testing.T) {
	loader := &packageLoader{}
	loader.buildContext.GOROOT = ""
	loader.gorootProblem = "no Go toolchain root could be found"

	if dir := loader.sourceDirectoryOf("fmt"); dir != "" {
		t.Errorf("the standard library was found at %q without a toolchain", dir)
	}
}

func TestToolchainRootOnPathFindsTheGoCommand(t *testing.T) {
	// The go command is the fallback when neither the caller nor the
	// environment names a toolchain. It lives in <root>/bin.
	root := t.TempDir()
	bin := filepath.Join(root, "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatalf("the fake toolchain could not be created: %v", err)
	}
	// The standard library's source is what makes a directory a toolchain.
	if err := os.MkdirAll(filepath.Join(root, "src"), 0o755); err != nil {
		t.Fatalf("the fake toolchain's source could not be created: %v", err)
	}
	name := "go"
	if runtime.GOOS == "windows" {
		name = "go.exe"
	}
	if err := os.WriteFile(filepath.Join(bin, name), []byte("not a real toolchain"), 0o755); err != nil {
		t.Fatalf("the fake go command could not be written: %v", err)
	}
	t.Setenv("PATH", bin)

	found := toolchainRootOnPath()

	if found != root {
		t.Errorf("the toolchain is %q, want %q", found, root)
	}
}

func TestResolveToolchainFallsBackToTheGoCommandOnThePath(t *testing.T) {
	root := t.TempDir()
	bin := filepath.Join(root, "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatalf("the fake toolchain could not be created: %v", err)
	}
	// The standard library's source is what makes a directory a toolchain.
	if err := os.MkdirAll(filepath.Join(root, "src"), 0o755); err != nil {
		t.Fatalf("the fake toolchain's source could not be created: %v", err)
	}
	name := "go"
	if runtime.GOOS == "windows" {
		name = "go.exe"
	}
	if err := os.WriteFile(filepath.Join(bin, name), []byte("not a real toolchain"), 0o755); err != nil {
		t.Fatalf("the fake go command could not be written: %v", err)
	}
	t.Setenv("PATH", bin)
	// The environment names nothing usable, so the go command is what is left.
	t.Setenv("GOROOT", filepath.Join(root, "removed-toolchain"))

	loader := &packageLoader{}
	loader.resolveToolchain("")

	if loader.goroot != root {
		t.Errorf("the toolchain is %q, want the one on the PATH %q", loader.goroot, root)
	}
	if loader.gorootProblem != "" {
		t.Errorf("a usable toolchain reported a problem: %s", loader.gorootProblem)
	}
}
