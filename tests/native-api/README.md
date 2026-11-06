`CheckWorkflowApi.java` checks eight public JVM method signatures against the actual YOYO `classes20.dex` using JADX's raw `MethodInfo`, not its display aliases. This caught the real `isSuccess()Z` getter, displayed as `getIsSuccess()` in decompiled Java.

The inspected APK matches the phone byte for byte: SHA256 `3b7fed0472a0d6c41fa664c43d3fdb4b4ba6e06a5374e97e34fa1cb8c2454bdd`, version 90.10.35.036 / 901035036. The APK and DEX are not included here.

Compile and run with JADX 1.5.6's full jar on the classpath and the extracted `classes20.dex` as the only argument. The production adapter also probes these APIs before submitting a save. This check establishes API availability, not successful execution.
