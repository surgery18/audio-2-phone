using System.Security.Cryptography;

namespace Audio2Phone.Streamer;

internal sealed class SessionStore
{
    private readonly string _path;
    private readonly object _gate = new();
    private readonly HashSet<string> _tokens;

    public SessionStore()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Audio2Phone");
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "sessions.txt");
        _tokens = File.Exists(_path)
            ? File.ReadAllLines(_path).Where(static line => line.Length >= 32).ToHashSet(StringComparer.Ordinal)
            : new HashSet<string>(StringComparer.Ordinal);
    }

    public bool IsTrusted(string token)
    {
        lock (_gate)
        {
            return _tokens.Contains(token);
        }
    }

    public string Create()
    {
        var bytes = RandomNumberGenerator.GetBytes(32);
        var token = Convert.ToHexString(bytes);
        lock (_gate)
        {
            _tokens.Add(token);
            File.WriteAllLines(_path, _tokens);
        }
        return token;
    }
}
