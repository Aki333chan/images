using System;
using System.IO;
using System.Text;

namespace Aurum.Companion.Core.Http
{
    internal sealed class BodyTooLargeException : IOException { }

    internal static class BoundedBody
    {
        public static string Read(Stream input, int maximumBytes)
        {
            using (var output = new MemoryStream())
            {
                var chunk = new byte[4096];
                int count;
                while ((count = input.Read(chunk, 0, Math.Min(chunk.Length, maximumBytes - (int)output.Length + 1))) > 0)
                {
                    if (output.Length + count > maximumBytes) throw new BodyTooLargeException();
                    output.Write(chunk, 0, count);
                }
                return new UTF8Encoding(false, true).GetString(output.ToArray());
            }
        }
    }
}
