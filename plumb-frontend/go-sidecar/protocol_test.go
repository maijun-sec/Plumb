package main

// Tests for the wire contract itself: the version handshake, the inline
// source text, and the framing.

import (
	"os"
	"path/filepath"
	"testing"
)

func TestVersionFailureRejectsAMissingVersion(t *testing.T) {
	// A request with no version is refused, not accepted as "probably
	// fine": the two halves are built independently, and a peer too old to
	// know about the handshake is exactly what the handshake exists to
	// catch.
	request := &Request{Op: "ping"}
	response := versionFailure(request)
	if response == nil {
		t.Fatal("a request with no protocol version was accepted")
	}
	if response.OK {
		t.Error("the refusal reported success")
	}
	if response.Error == "" {
		t.Error("the refusal carried no message")
	}
}

func TestVersionFailureRejectsAMismatchedVersion(t *testing.T) {
	version := 99
	request := &Request{Op: "openProject", ProtocolVersion: &version}
	response := versionFailure(request)
	if response == nil {
		t.Fatal("a request for another protocol version was accepted")
	}
	if response.OK {
		t.Error("the refusal reported success")
	}
}

func TestVersionFailureAcceptsTheCurrentVersion(t *testing.T) {
	version := ProtocolVersionValue
	ir := IRVersionValue
	request := &Request{Op: "ping", ProtocolVersion: &version, IRVersion: &ir}
	if response := versionFailure(request); response != nil {
		t.Fatalf("a request for this sidecar's version was refused: %s", response.Error)
	}
}

func TestEveryResponseCarriesTheVersions(t *testing.T) {
	// The Java side refuses a sidecar that reports no version, so a
	// response without the fields would make the whole sidecar unusable.
	response := &Response{Op: "ping", OK: true}
	payload, err := encodeResponse(response)
	if err != nil {
		t.Fatalf("the response could not be encoded: %v", err)
	}
	text := string(payload)
	for _, needle := range []string{`"protocolVersion":1`, `"irVersion":1`} {
		if !contains(text, needle) {
			t.Errorf("the response does not carry %s: %s", needle, text)
		}
	}
}

func TestAFailedResponseCarriesTheVersionsToo(t *testing.T) {
	response := &Response{Op: "translate", OK: false, Error: "no project is open"}
	payload, err := encodeResponse(response)
	if err != nil {
		t.Fatalf("the response could not be encoded: %v", err)
	}
	text := string(payload)
	for _, needle := range []string{`"protocolVersion":1`, `"irVersion":1`, `"ok":false`} {
		if !contains(text, needle) {
			t.Errorf("the failed response does not carry %s: %s", needle, text)
		}
	}
}

func TestTheCallersTextWinsOverTheDisk(t *testing.T) {
	// The caller's copy of a file may be an unsaved buffer, and it may
	// differ from the disk's copy. The analysis must see the caller's
	// copy: otherwise the findings describe a version of the file the
	// caller never saw.
	directory := t.TempDir()
	path := filepath.Join(directory, "sample.go")

	onDisk := "package sample\n\nfunc onDisk() {\n}\n"
	if err := os.WriteFile(path, []byte(onDisk), 0o644); err != nil {
		t.Fatalf("the file could not be written: %v", err)
	}

	inMemory := "package sample\n\nfunc inMemory() {\n}\n"

	version := ProtocolVersionValue
	request := &Request{
		Op:              "openProject",
		ProtocolVersion: &version,
		ProjectID:       "test",
		Sources:         []SourceFile{{Path: path, Text: inMemory}},
		Packages: []PackageSpec{{
			ImportPath: "sample",
			Dir:        directory,
			Files:      []string{path},
		}},
	}

	session, err := Open(request)
	if err != nil {
		t.Fatalf("the project could not be opened: %v", err)
	}

	functions := session.Translate(path)
	names := map[string]bool{}
	for _, function := range functions {
		names[function.Name] = true
	}
	if !names["inMemory"] {
		t.Errorf("the caller's text was not the one analysed: the functions are %v", names)
	}
	if names["onDisk"] {
		t.Errorf("the disk's text was analysed instead of the caller's: the functions are %v", names)
	}
}

// contains is a tiny helper, so that the test does not depend on a
// particular standard library version's strings helpers.
func contains(haystack string, needle string) bool {
	for index := 0; index+len(needle) <= len(haystack); index++ {
		if haystack[index:index+len(needle)] == needle {
			return true
		}
	}
	return false
}
