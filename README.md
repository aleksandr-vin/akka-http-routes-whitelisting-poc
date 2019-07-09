# Akka Http routes whitelisting PoC
 
Assuming there is a [WebServerSpec.someProtection](src/test/scala/WebServerSpec.scala) directive,
that protects its inner route, the safety harness here ensures that no response will have way out
of the server unless it is 'protected' aka wrapped with the protection directive.
 
The idea is to add intermediate http response header for each explicitly 'protected' route
(compare [WebServerSpec.someProtection](src/test/scala/WebServerSpec.scala) to
[WebServerSpec.safeProtection](src/test/scala/WebServerSpec.scala)),
'whitelisting' the route, and reject responses lacking this header at top-most common directive
(see [SafetyHarness.dropNotWhitelistedResponses](src/test/scala/WebServerSpec.scala)).
