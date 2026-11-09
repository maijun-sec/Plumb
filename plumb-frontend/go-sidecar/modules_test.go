package main

// Tests for the module cache: where a third-party dependency's source lives.
//
// WHY THESE TESTS EXIST. A third-party dependency is neither under the
// toolchain's root nor in GOPATH, so go/build cannot find it: the cache is
// keyed by module path and version, while the import path names a package.
// Without this lookup every third-party import goes unresolved, and an
// unresolved import is a missing finding.

import (
	"os"
	"path/filepath"
	"testing"
)

// writeModule puts a module into a cache, the way the toolchain lays it out.
//
// The directory name is ESCAPED, because that is how the cache stores it:
// an uppercase letter is written as an exclamation mark and the lowercase
// letter.
func writeModule(t *testing.T, cache string, directory string, version string) string {
	t.Helper()

	moduleDir := filepath.Join(cache, filepath.FromSlash(escapeModulePath(directory))+"@"+version)
	if err := os.MkdirAll(moduleDir, 0o755); err != nil {
		t.Fatalf("the module %s could not be created: %v", directory, err)
	}
	source := filepath.Join(moduleDir, "package.go")
	if err := os.WriteFile(source, []byte("package dep\n"), 0o644); err != nil {
		t.Fatalf("the module's source could not be written: %v", err)
	}
	return moduleDir
}

func TestModuleDirectoryOfFindsAModule(t *testing.T) {
	cache := t.TempDir()
	moduleDir := writeModule(t, cache, "example.com/dep", "v1.2.0")

	found := moduleDirectoryOf(cache, "example.com/dep")

	if found != moduleDir {
		t.Errorf("the module was found at %q, want %q", found, moduleDir)
	}
}

func TestModuleDirectoryOfFindsAPackageInsideAModule(t *testing.T) {
	cache := t.TempDir()
	moduleDir := writeModule(t, cache, "example.com/dep", "v1.2.0")
	sub := filepath.Join(moduleDir, "sub")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatalf("the package directory could not be created: %v", err)
	}

	found := moduleDirectoryOf(cache, "example.com/dep/sub")

	if found != sub {
		t.Errorf("the package was found at %q, want %q", found, sub)
	}
}

func TestModuleDirectoryOfPrefersTheNewestVersion(t *testing.T) {
	cache := t.TempDir()
	writeModule(t, cache, "example.com/dep", "v1.9.0")
	newest := writeModule(t, cache, "example.com/dep", "v1.10.0")

	found := moduleDirectoryOf(cache, "example.com/dep")

	// v1.10.0 is newer than v1.9.0, but a lexicographic comparison would say
	// the opposite, and reading the wrong version means reading the wrong
	// source.
	if found != newest {
		t.Errorf("the module was found at %q, want the newest %q", found, newest)
	}
}

func TestModuleDirectoryOfEscapesUppercaseLetters(t *testing.T) {
	cache := t.TempDir()
	moduleDir := writeModule(t, cache, "example.com/Azure", "v1.0.0")

	found := moduleDirectoryOf(cache, "example.com/Azure")

	if found != moduleDir {
		t.Errorf("the module was found at %q, want %q", found, moduleDir)
	}
}

func TestModuleDirectoryOfReturnsNothingForAnUnknownModule(t *testing.T) {
	cache := t.TempDir()
	writeModule(t, cache, "example.com/dep", "v1.2.0")

	if found := moduleDirectoryOf(cache, "example.com/other"); found != "" {
		t.Errorf("an unknown module was found at %q", found)
	}
}

func TestModuleCacheDirectoryFollowsTheEnvironment(t *testing.T) {
	cache := t.TempDir()
	t.Setenv("GOMODCACHE", cache)
	t.Setenv("GOPATH", "")

	if found := moduleCacheDirectory(); found != cache {
		t.Errorf("the module cache is %q, want %q", found, cache)
	}
}

func TestCompareVersionsOrdersVersionsNumerically(t *testing.T) {
	cases := []struct {
		left string
		right string
		want int
	}{
		{"v1.10.0", "v1.9.0", 1},
		{"v1.9.0", "v1.10.0", -1},
		{"v1.2.0", "v1.2.0", 0},
		{"v1.2.0", "v1.2.0-rc1", 1},
		{"v1.2.0-rc1", "v1.2.0", -1},
		{"v2.0.0", "v1.99.99", 1},
		{"v1.2", "v1.2.0", 0},
	}

	for _, test := range cases {
		if got := compareVersions(test.left, test.right); got != test.want {
			t.Errorf("%s against %s: %d, want %d", test.left, test.right, got, test.want)
		}
	}
}

// WHY THESE TESTS EXIST. A REPLACED dependency (`replace other.example/mod
// => ../repo`) and a dependency that belongs to another module of a WORKSPACE
// are directories on disk, not downloads: the module cache does not hold
// them, and go/build does not know about them. The Java side reads the
// module's own build description -- the only place the replacement is
// written down -- and names the directory. Without this lookup the
// dependency's types are unknown and every call into it is unresolved, which
// is a missing finding rather than a missing precision.

func TestLocalModuleDirectoryFindsAPackageInsideAModule(t *testing.T) {
	root := t.TempDir()
	packageDir := filepath.Join(root, "pkg", "lib")
	if err := os.MkdirAll(packageDir, 0o755); err != nil {
		t.Fatalf("the package directory could not be created: %v", err)
	}
	loader := &packageLoader{modules: map[string]string{"example.com/lib": root}}

	found := loader.localModuleDirectory("example.com/lib/pkg/lib")

	if found != packageDir {
		t.Errorf("the package was found at %q, want %q", found, packageDir)
	}
}

func TestLocalModuleDirectoryFindsTheModuleItself(t *testing.T) {
	root := t.TempDir()
	loader := &packageLoader{modules: map[string]string{"example.com/lib": root}}

	found := loader.localModuleDirectory("example.com/lib")

	if found != root {
		t.Errorf("the module was found at %q, want %q", found, root)
	}
}

func TestLocalModuleDirectoryPrefersTheLongestModulePath(t *testing.T) {
	short := t.TempDir()
	long := t.TempDir()
	longPackage := filepath.Join(long, "pkg")
	if err := os.MkdirAll(longPackage, 0o755); err != nil {
		t.Fatalf("the package directory could not be created: %v", err)
	}
	loader := &packageLoader{modules: map[string]string{
		"example.com/m":  short,
		"example.com/m2": long,
	}}

	found := loader.localModuleDirectory("example.com/m2/pkg")

	// The module named "example.com/m" must not swallow the import
	// "example.com/m2/pkg": the prefix has to end at a path separator.
	if found != longPackage {
		t.Errorf("the package was found at %q, want %q", found, longPackage)
	}
}

func TestLocalModuleDirectoryReturnsNothingForAnUnknownModule(t *testing.T) {
	loader := &packageLoader{modules: map[string]string{"example.com/lib": t.TempDir()}}

	if found := loader.localModuleDirectory("fmt"); found != "" {
		t.Errorf("an unknown module was found at %q", found)
	}
}

func TestLocalModuleDirectoryReturnsNothingWithoutModules(t *testing.T) {
	loader := &packageLoader{modules: map[string]string{}}

	if found := loader.localModuleDirectory("example.com/lib"); found != "" {
		t.Errorf("a module was found at %q without a module map", found)
	}
}
