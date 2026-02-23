using System.Collections.Generic;
using McpExtensionForUnity;
using NUnit.Framework;

namespace McpExtensionForUnity.Tests
{
    [TestFixture]
    public class McpValidationTest
    {
        [Test]
        public void HasValidAssemblyNames_EmptyList_ReturnsFalse()
        {
            var result = McpValidation.HasValidAssemblyNames(new List<string>());
            Assert.That(result, Is.False);
        }

        [Test]
        public void HasValidAssemblyNames_ListWithEmptyString_ReturnsFalse()
        {
            var result = McpValidation.HasValidAssemblyNames(new List<string> { "" });
            Assert.That(result, Is.False);
        }

        [Test]
        public void HasValidAssemblyNames_ListWithWhitespace_ReturnsFalse()
        {
            var result = McpValidation.HasValidAssemblyNames(new List<string> { "   ", "\t" });
            Assert.That(result, Is.False);
        }

        [Test]
        public void HasValidAssemblyNames_ValidName_ReturnsTrue()
        {
            var result = McpValidation.HasValidAssemblyNames(new List<string> { "MyTests.EditMode" });
            Assert.That(result, Is.True);
        }

        [Test]
        public void HasValidAssemblyNames_MixedValidAndBlank_ReturnsTrue()
        {
            var result = McpValidation.HasValidAssemblyNames(new List<string> { "", "MyTests.EditMode", "  " });
            Assert.That(result, Is.True);
        }
    }
}
