module github.com/plumb/plumb-go-sidecar

// The Go version is declared conservatively: the sidecar uses nothing newer
// than the 1.21 language and library surface, so it builds with any toolchain
// from 1.21 onwards. The toolchain that READS the export data may be newer
// than this one -- see README.md.
go 1.21
