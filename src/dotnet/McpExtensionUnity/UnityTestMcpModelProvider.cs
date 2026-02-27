using JetBrains.Application.Components;
using JetBrains.Application.Parts;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd;
using McpExtensionUnity.Model;

namespace McpExtensionUnity
{
    // Creates and exposes the UnityTestMcpModel bound to the solution protocol.
    // Both UnityTestMcpHandler and UnityCompilationMcpHandler inject this provider
    // to share the single model instance (Rd protocol binding must happen exactly once).
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class UnityTestMcpModelProvider
    {
        public readonly UnityTestMcpModel Model;

        public UnityTestMcpModelProvider(Lifetime lifetime, IProtocol protocol)
        {
            Model = new UnityTestMcpModel(lifetime, protocol);
        }
    }
}
