package main

// The sidecar's entry point: a request/response loop over stdio.
//
// The process reads one frame at a time, dispatches it, and writes one frame
// back. It exits when the pipe closes or when it is asked to shut down.
//
// EVERY FAILURE IS REPORTED, NEVER SWALLOWED. A sidecar that dies silently
// would leave the Java side believing the project had no findings. So an
// error becomes an `ok: false` response, and the process stays alive to
// answer the next request.

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"strings"
)

const (
	// The number of bytes in the length prefix.
	lengthPrefixBytes = 4

	// The largest payload accepted. A frame beyond this is refused rather
	// than allocated: a corrupt length must not become an out-of-memory
	// kill.
	maxPayloadBytes = 256 * 1024 * 1024
)

// main runs the request loop.
func main() {
	// The sidecar's own chatter goes to stderr, never to stdout: stdout
	// carries the framed protocol, and a stray line would corrupt it.
	stdout := bufio.NewWriter(os.Stdout)
	stdin := bufio.NewReader(os.Stdin)

	var session *ProjectSession

	for {
		payload, err := readFrame(stdin)
		if err != nil {
			writeError(stdout, "read", err.Error())
			break
		}
		if payload == nil {
			// The peer closed the pipe: a clean end.
			break
		}

		request, err := decodeRequest(payload)
		if err != nil {
			writeError(stdout, "decode", err.Error())
			continue
		}

		// The version gate comes FIRST, before anything else happens --
		// including the shutdown. A peer that speaks another protocol must
		// be told so, not obeyed.
		if response := versionFailure(request); response != nil {
			if err := writeResponse(stdout, response); err != nil {
				break
			}
			continue
		}

		if strings.EqualFold(request.Op, "shutdown") {
			break
		}

		response := dispatchSafely(request, &session)
		if err := writeResponse(stdout, response); err != nil {
			break
		}
	}
}

// versionFailure checks a request's version against this sidecar's.
//
// A request that names a protocol version this sidecar does not speak is
// REFUSED, not interpreted -- and a request that names NO version is
// refused too.
//
// The missing case is refused for the same reason the Java side refuses a
// response with no version: the two halves are built independently, and a
// peer too old to know about the handshake is exactly the case the handshake
// exists to catch. Accepting it would mean interpreting a request whose
// shape this sidecar cannot know -- and a finding that goes missing that
// way leaves no trace at all.
func versionFailure(request *Request) *Response {
	if request.ProtocolVersion == nil {
		return &Response{
			Op: request.Op,
			OK: false,
			Error: fmt.Sprintf(
				"the request carries no protocol version; this sidecar speaks version %d",
				ProtocolVersionValue),
		}
	}
	if *request.ProtocolVersion != ProtocolVersionValue {
		return &Response{
			Op: request.Op,
			OK: false,
			Error: fmt.Sprintf(
				"this sidecar speaks protocol version %d, and the request names %d",
				ProtocolVersionValue, *request.ProtocolVersion),
		}
	}
	if request.IRVersion != nil && *request.IRVersion != IRVersionValue {
		return &Response{
			Op: request.Op,
			OK: false,
			Error: fmt.Sprintf(
				"this sidecar was built against IR version %d, and the request names %d",
				IRVersionValue, *request.IRVersion),
		}
	}
	return nil
}

// dispatch answers one request.
//
// Every branch returns a response. There is no path that returns nothing,
// because an unanswered request would leave the Java side waiting forever
// for a frame that is never coming.
func dispatch(request *Request, session **ProjectSession) *Response {
	switch strings.ToLower(request.Op) {
	case "ping":
		return &Response{Op: "ping", OK: true}

	case "openproject":
		opened, err := Open(request)
		if err != nil {
			return &Response{
				Op:    request.Op,
				OK:    false,
				Error: fmt.Sprintf("%v", err),
			}
		}
		*session = opened
		return &Response{
			Op:          request.Op,
			OK:          true,
			Diagnostics: opened.Diagnostics(),
		}

	case "translate":
		if *session == nil {
			return &Response{
				Op:    request.Op,
				OK:    false,
				Error: "no project is open: send openProject first",
			}
		}
		if strings.TrimSpace(request.Path) == "" {
			return &Response{
				Op:    request.Op,
				OK:    false,
				Error: "the translate request carried no path",
			}
		}
		return &Response{
			Op:        request.Op,
			OK:        true,
			Functions: (*session).Translate(request.Path),
		}

	case "closeproject":
		*session = nil
		return &Response{Op: request.Op, OK: true}

	default:
		return &Response{
			Op:    request.Op,
			OK:    false,
			Error: fmt.Sprintf("unknown operation %q", request.Op),
		}
	}
}

// dispatchSafely answers one request, turning a panic into a failure.
//
// An unexpected failure inside the translation is reported as a failed
// response, NOT as a crash. The Java side turns it into a structured
// problem, and the session survives -- a panic in one file must not cost the
// analysis of every other file in the project.
func dispatchSafely(request *Request, session **ProjectSession) (response *Response) {
	defer func() {
		if recovered := recover(); recovered != nil {
			response = &Response{
				Op:    request.Op,
				OK:    false,
				Error: fmt.Sprintf("the sidecar failed while handling the request: %v", recovered),
			}
			logf("the request %q failed: %v", request.Op, recovered)
		}
	}()
	return dispatch(request, session)
}

// readFrame reads one frame from the reader.
//
// It returns nil, nil at a clean end of stream: the peer closed the pipe.
func readFrame(reader *bufio.Reader) ([]byte, error) {
	prefix := make([]byte, lengthPrefixBytes)
	read, err := io.ReadFull(reader, prefix)
	if err != nil {
		if err == io.EOF && read == 0 {
			return nil, nil
		}
		if err == io.ErrUnexpectedEOF {
			return nil, fmt.Errorf(
				"the frame length prefix was truncated: got %d of %d bytes", read, lengthPrefixBytes)
		}
		return nil, err
	}

	length := int(binary.BigEndian.Uint32(prefix))
	if length < 0 || length > maxPayloadBytes {
		return nil, fmt.Errorf("the frame length %d is out of range", length)
	}

	payload := make([]byte, length)
	if _, err := io.ReadFull(reader, payload); err != nil {
		return nil, fmt.Errorf("the frame payload was truncated: %w", err)
	}
	return payload, nil
}

// writeFrame writes one frame to the writer.
func writeFrame(writer *bufio.Writer, payload []byte) error {
	prefix := make([]byte, lengthPrefixBytes)
	binary.BigEndian.PutUint32(prefix, uint32(len(payload)))
	if _, err := writer.Write(prefix); err != nil {
		return err
	}
	if _, err := writer.Write(payload); err != nil {
		return err
	}
	return writer.Flush()
}

// writeResponse writes a response frame.
func writeResponse(writer *bufio.Writer, response *Response) error {
	payload, err := encodeResponse(response)
	if err != nil {
		// The response could not be encoded. A half-written frame would be
		// read as a valid frame with a truncated payload, so the failure is
		// reported as a failure instead.
		fallback, fallbackErr := encodeResponse(&Response{
			Op:    response.Op,
			OK:    false,
			Error: fmt.Sprintf("the response could not be encoded: %v", err),
		})
		if fallbackErr != nil {
			return fallbackErr
		}
		payload = fallback
	}
	return writeFrame(writer, payload)
}

// writeError writes a failure response for a frame that could not be
// handled at all.
func writeError(writer *bufio.Writer, op string, message string) {
	payload, err := encodeResponse(&Response{Op: op, OK: false, Error: message})
	if err != nil {
		return
	}
	// There is nothing to report to if the pipe is already gone.
	_ = writeFrame(writer, payload)
}

// logf writes a line to stderr.
//
// stderr is the sidecar's log: the protocol is on stdout, so a stray line
// there would corrupt the frame stream.
func logf(format string, arguments ...interface{}) {
	fmt.Fprintf(os.Stderr, "plumb-go-sidecar: "+format+"\n", arguments...)
}
