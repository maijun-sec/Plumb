using System.Buffers.Binary;
using System.Text;
using System.Text.Json;

namespace Plumb.CSharpSidecar;

/// <summary>
/// The length-prefixed JSON framing.
/// </summary>
/// <remarks>
/// <para>
/// A frame is a 4-byte big-endian length followed by that many UTF-8 payload
/// bytes. See <c>PROTOCOL.md</c> for why the framing is explicit rather than
/// line-oriented.
/// </para>
/// </remarks>
public static class Framing
{
    /// <summary>The number of bytes in the length prefix.</summary>
    public const int LengthPrefixBytes = 4;

    /// <summary>
    /// The largest payload accepted. A frame beyond this is refused rather than
    /// allocated: a corrupt length must not become an out-of-memory kill.
    /// </summary>
    public const int MaxPayloadBytes = 256 * 1024 * 1024;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        // Property names come from the attributes, so the naming policy is fixed.
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false,
    };

    /// <summary>Reads one frame from the stream, or returns null at end of stream.</summary>
    /// <param name="stream">the stream to read from</param>
    /// <param name="cancellationToken">the cancellation token</param>
    /// <returns>the payload, or null when the stream ended cleanly</returns>
    public static async Task<string?> ReadFrameAsync(
        Stream stream,
        CancellationToken cancellationToken)
    {
        var prefix = new byte[LengthPrefixBytes];
        int read = await ReadExactAsync(stream, prefix, cancellationToken).ConfigureAwait(false);
        if (read == 0)
        {
            // A clean end of stream: the peer closed the pipe.
            return null;
        }
        if (read < LengthPrefixBytes)
        {
            throw new IOException(
                $"the frame length prefix was truncated: got {read} of {LengthPrefixBytes} bytes");
        }

        int length = BinaryPrimitives.ReadInt32BigEndian(prefix);
        if (length < 0 || length > MaxPayloadBytes)
        {
            throw new IOException($"the frame length {length} is out of range");
        }

        var payload = new byte[length];
        int payloadRead = await ReadExactAsync(stream, payload, cancellationToken).ConfigureAwait(false);
        if (payloadRead < length)
        {
            throw new IOException(
                $"the frame payload was truncated: got {payloadRead} of {length} bytes");
        }

        return Encoding.UTF8.GetString(payload);
    }

    /// <summary>Writes one frame to the stream.</summary>
    /// <param name="stream">the stream to write to</param>
    /// <param name="payload">the payload text</param>
    /// <param name="cancellationToken">the cancellation token</param>
    public static async Task WriteFrameAsync(
        Stream stream,
        string payload,
        CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(payload);
        var prefix = new byte[LengthPrefixBytes];
        BinaryPrimitives.WriteInt32BigEndian(prefix, bytes.Length);

        await stream.WriteAsync(prefix, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Serialises a response into a frame payload.</summary>
    /// <param name="response">the response to serialise</param>
    /// <returns>the JSON text</returns>
    public static string Encode(Response response) => JsonSerializer.Serialize(response, JsonOptions);

    /// <summary>Deserialises a request from a frame payload.</summary>
    /// <param name="payload">the JSON text</param>
    /// <returns>the request</returns>
    public static Request DecodeRequest(string payload) =>
        JsonSerializer.Deserialize<Request>(payload, JsonOptions)
        ?? throw new IOException("the request payload was null");

    private static async Task<int> ReadExactAsync(
        Stream stream,
        byte[] buffer,
        CancellationToken cancellationToken)
    {
        int total = 0;
        while (total < buffer.Length)
        {
            int n = await stream
                .ReadAsync(buffer.AsMemory(total, buffer.Length - total), cancellationToken)
                .ConfigureAwait(false);
            if (n == 0)
            {
                return total;
            }
            total += n;
        }
        return total;
    }
}
