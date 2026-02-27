using JetBrains.Application.Components;
using JetBrains.Application.Parts;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd;
using McpExtensionUnity.Model;

namespace McpExtensionUnity
{
    // Creates and exposes the UnityCompilationMcpModel bound to the solution protocol.
    // UnityCompilationMcpHandler injects this provider to share the single model instance
    // (Rd protocol binding must happen exactly once).
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class UnityCompilationMcpModelProvider
    {
        public readonly UnityCompilationMcpModel Model;

        public UnityCompilationMcpModelProvider(Lifetime lifetime, IProtocol protocol)
        {
            Model = new UnityCompilationMcpModel(lifetime, protocol);
        }
    }
}
