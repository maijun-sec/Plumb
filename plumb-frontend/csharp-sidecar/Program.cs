using System.Text;

namespace Plumb.CSharpSidecar;

/// <summary>
/// The sidecar's entry point: a request/response loop over stdio.
/// </summary>
/// <remarks>
/// <para>
/// The process reads one frame at a time, dispatches it, and writes one frame
/// back. It exits when the pipe closes or when it is asked to shut down.
/// </para>
/// <para>
/// <b>Every failure is reported, never swallowed.</b> A sidecar that dies
/// silently would leave the Java side believing the project had no findings.
/// So an error becomes an <c>ok: false</c> response, and the process stays
/// alive to answer the next request.
/// </para>
/// </remarks>
public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        // The sidecar's own chatter goes to stderr, never to stdout: stdout
        // carries the framed protocol, and a stray line would corrupt it.
        using var stdout = Console.OpenStandardOutput();
        using var stdin = Console.OpenStandardInput();

        using var cancellation = new CancellationTokenSource();
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            cancellation.Cancel();
        };

        ProjectSession? session = null;

        try
        {
            while (!cancellation.IsCancellationRequested)
            {
                string? payload;
                try
                {
                    payload = await Framing.ReadFrameAsync(stdin, cancellation.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (IOException ex)
                {
                    await WriteErrorAsync(stdout, "read", ex.Message, cancellation.Token)
                        .ConfigureAwait(false);
                    break;
                }

                if (payload is null)
                {
                    // The peer closed the pipe: a clean end.
                    break;
                }

                Request request;
                try
                {
                    request = Framing.DecodeRequest(payload);
                }
                catch (Exception ex)
                {
                    await WriteErrorAsync(stdout, "decode", ex.Message, cancellation.Token)
                        .ConfigureAwait(false);
                    continue;
                }

                if (request.Op.Equals("shutdown", StringComparison.OrdinalIgnoreCase))
                {
                    break;
                }

                Response response;
                try
                {
                    response = Dispatch(request, ref session);
                }
                catch (Exception ex)
                {
                    // An unexpected failure inside the translation is reported as
                    // a failed response, NOT as a crash. The Java side turns it
                    // into a structured problem, and the session survives.
                    response = new Response
                    {
                        Op = request.Op,
                        Ok = false,
                        Error = $"{ex.GetType().Name}: {ex.Message}",
                    };
                }

                try
                {
                    await Framing.WriteFrameAsync(stdout, Framing.Encode(response), cancellation.Token)
                        .ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or OperationCanceledException)
                {
                    break;
                }
            }
        }
        finally
        {
            session = null;
        }

        return 0;
    }

    private static Response Dispatch(Request request, ref ProjectSession? session)
    {
        // A request that names a protocol version this sidecar does not speak is
        // refused, rather than interpreted. The two halves are built
        // independently, and half-understanding each other is how a finding goes
        // missing without a trace.
        if (request.ProtocolVersion.HasValue && request.ProtocolVersion.Value != Versions.PROTOCOL)
        {
            return new Response
            {
                Op = request.Op,
                Ok = false,
                Error = $"this sidecar speaks protocol version {Versions.PROTOCOL}, and the request names {request.ProtocolVersion.Value}",
            };
        }

        switch (request.Op.ToLowerInvariant())
        {
            case "ping":
                return new Response { Op = "ping", Ok = true };

            case "openproject":
            {
                session = ProjectSession.Open(request);
                return new Response
                {
                    Op = request.Op,
                    Ok = true,
                    Diagnostics = session.Diagnostics(),
                };
            }

            case "translate":
            {
                if (session is null)
                {
                    return new Response
                    {
                        Op = request.Op,
                        Ok = false,
                        Error = "no project is open: send openProject first",
                    };
                }
                if (string.IsNullOrWhiteSpace(request.Path))
                {
                    return new Response
                    {
                        Op = request.Op,
                        Ok = false,
                        Error = "the translate request carried no path",
                    };
                }
                return new Response
                {
                    Op = request.Op,
                    Ok = true,
                    Functions = session.Translate(request.Path),
                };
            }

            case "closeproject":
            {
                session = null;
                return new Response { Op = request.Op, Ok = true };
            }

            default:
                return new Response
                {
                    Op = request.Op,
                    Ok = false,
                    Error = $"unknown operation '{request.Op}'",
                };
        }
    }

    private static async Task WriteErrorAsync(
        Stream stdout,
        string op,
        string message,
        CancellationToken cancellationToken)
    {
        var response = new Response { Op = op, Ok = false, Error = message };
        try
        {
            await Framing.WriteFrameAsync(stdout, Framing.Encode(response), cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception)
        {
            // The pipe is already gone; there is nothing left to report to.
        }
    }
}
