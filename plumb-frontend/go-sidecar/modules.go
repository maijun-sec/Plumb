package main

// The module cache: where a third-party dependency's source lives.
//
// WHY THIS EXISTS. The standard library is found under the toolchain's root,
// and go/build knows how to look there. A third-party dependency is NOT
// there: it lives in the module cache, under a path go/build does not know
// about. The cache is keyed by MODULE PATH and VERSION, while the import
// path names a PACKAGE -- which may be a package inside a module, and may
// even be the module itself.
//
// So the cache is searched directly. The layout is the toolchain's own:
//
//	$GOMODCACHE/<module path>@<version>/<package path inside the module>
//
// with one twist: an uppercase letter in the module path is stored as an
// exclamation mark followed by the lowercase letter, because a file name may
// not contain an uppercase letter on every file system the toolchain
// supports.
//
// WHY THE NEWEST VERSION IS NOT ENOUGH ON ITS OWN. A module cache usually
// holds several versions of the same module, and reading the wrong one would
// give the analysis types the program was never compiled against. So the
// versions are ordered, and the newest is the one that is read: it is the
// one a build would use when nothing pins an older one, and the project's
// own go.mod pins the rest.

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// moduleCacheDirectory returns the module cache's directory, or "" when there
// is none.
//
// The order is the environment's own: GOMODCACHE wins, then the first entry
// of GOPATH, then the default under the home directory. A candidate that does
// not name a directory is not a cache.
func moduleCacheDirectory() string {
	candidates := []string{}
	if value := os.Getenv("GOMODCACHE"); value != "" {
		candidates = append(candidates, value)
	}
	if value := os.Getenv("GOPATH"); value != "" {
		for _, entry := range filepath.SplitList(value) {
			if entry != "" {
				candidates = append(candidates, filepath.Join(entry, "pkg", "mod"))
			}
		}
	}
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		candidates = append(candidates, filepath.Join(home, "go", "pkg", "mod"))
	}
	for _, candidate := range candidates {
		if isDirectory(candidate) {
			return candidate
		}
	}
	return ""
}

// escapeModulePath encodes a module path for the cache's directory names.
//
// "github.com/Azure" becomes "github.com/!azure".
func escapeModulePath(path string) string {
	escaped := strings.Builder{}
	for _, character := range path {
		if character >= 'A' && character <= 'Z' {
			escaped.WriteRune('!')
			escaped.WriteRune(character + ('a' - 'A'))
			continue
		}
		escaped.WriteRune(character)
	}
	return escaped.String()
}

// moduleDirectoryOf finds a dependency's source directory in the module cache.
//
// The import path may name a package INSIDE a module, so every prefix of it
// is tried as a module path: "github.com/example/project/sub" may be a
// package of the module "github.com/example/project".
func moduleDirectoryOf(cache string, path string) string {
	if cache == "" || path == "" {
		return ""
	}
	segments := strings.Split(path, "/")
	for count := len(segments); count > 0; count-- {
		modulePath := strings.Join(segments[:count], "/")
		rest := strings.Join(segments[count:], "/")
		version := newestVersionIn(cache, modulePath)
		if version == "" {
			continue
		}
		directory := filepath.Join(cache, filepath.FromSlash(escapeModulePath(modulePath))+"@"+version)
		if rest != "" {
			directory = filepath.Join(directory, filepath.FromSlash(rest))
		}
		if isDirectory(directory) {
			return directory
		}
	}
	return ""
}

// newestVersionIn returns the newest version of a module in the cache, or "".
func newestVersionIn(cache string, modulePath string) string {
	escaped := escapeModulePath(modulePath)
	pattern := filepath.Join(cache, filepath.FromSlash(escaped)) + "@*"
	matches, err := filepath.Glob(pattern)
	if err != nil {
		return ""
	}
	prefix := escaped + "@"
	newest := ""
	for _, match := range matches {
		if !isDirectory(match) {
			continue
		}
		slash := filepath.ToSlash(match)
		index := strings.LastIndex(slash, prefix)
		if index < 0 {
			continue
		}
		version := slash[index+len(prefix):]
		if version == "" {
			continue
		}
		if newest == "" || compareVersions(version, newest) > 0 {
			newest = version
		}
	}
	return newest
}

// compareVersions orders two module versions.
//
// The comparison is numeric, segment by segment, so v1.10.0 is newer than
// v1.9.0. A lexicographic comparison would get that wrong, and reading the
// wrong version means reading the wrong source.
//
// A pre-release version is older than the release it leads to: v1.2.0-rc1 is
// older than v1.2.0.
func compareVersions(left string, right string) int {
	leftCore, leftPre := splitVersion(left)
	rightCore, rightPre := splitVersion(right)
	leftParts := strings.Split(leftCore, ".")
	rightParts := strings.Split(rightCore, ".")

	length := len(leftParts)
	if len(rightParts) > length {
		length = len(rightParts)
	}
	for index := 0; index < length; index++ {
		leftNumber := versionSegment(leftParts, index)
		rightNumber := versionSegment(rightParts, index)
		if leftNumber != rightNumber {
			if leftNumber < rightNumber {
				return -1
			}
			return 1
		}
	}
	switch {
	case leftPre == rightPre:
		return 0
	case leftPre == "":
		return 1
	case rightPre == "":
		return -1
	case leftPre < rightPre:
		return -1
	default:
		return 1
	}
}

// splitVersion separates a version's numbers from its pre-release suffix.
func splitVersion(version string) (string, string) {
	trimmed := strings.TrimPrefix(version, "v")
	if index := strings.Index(trimmed, "+"); index >= 0 {
		trimmed = trimmed[:index]
	}
	if index := strings.Index(trimmed, "-"); index >= 0 {
		return trimmed[:index], trimmed[index+1:]
	}
	return trimmed, ""
}

// versionSegment returns one numeric segment of a version, or zero.
func versionSegment(parts []string, index int) int {
	if index >= len(parts) {
		return 0
	}
	value, err := strconv.Atoi(parts[index])
	if err != nil {
		return 0
	}
	return value
}
