using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.RdBackend.Common.Env;

namespace McpExtensionForUnity
{
    // Zone marker: the class name "ZoneMarker" is the convention used by JetBrains' zone system.
    // Using [ZoneMarkerAttribute] (not [ZoneMarker]) to avoid conflict with this class name.
    [ZoneMarkerAttribute]
    public class ZoneMarker : IRequire<IReSharperHostCoreSharedFeatureZone>
    {
    }
}
