using System.Collections.Generic;

namespace McpExtensionForUnity
{
    // Pure validation logic with no Rider SDK dependencies.
    // Kept in a separate file so tests can compile it directly without referencing the Rider-dependent main DLL.
    internal static class McpValidation
    {
        internal static bool HasValidAssemblyNames(IList<string> assemblyNames)
        {
            foreach (var name in assemblyNames)
            {
                if (!string.IsNullOrWhiteSpace(name))
                    return true;
            }
            return false;
        }
    }
}
