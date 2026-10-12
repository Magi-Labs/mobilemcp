package labs.magi.mobilemcp
import org.junit.Assert.*
import org.junit.Test
class CommandRunnerTest {
 @Test fun argvDoesNotImplicitlyInterpretShellSyntax() {
  val r = CommandRunner().run(arrayOf("/bin/echo", "hello; exit 7"),1000)
  assertEquals(0,r.exitCode); assertEquals("hello; exit 7\n",r.output); assertFalse(r.timedOut)
 }
 @Test fun capturesFailureAndMergedStderr() {
  val r=CommandRunner().run(arrayOf("/bin/sh","-c","echo failed >&2; exit 7"),1000)
  assertEquals(7,r.exitCode);assertTrue(r.output.contains("failed"))
 }
 @Test fun timeoutHasUnknownExitCode() {
  val r=CommandRunner().run(arrayOf("/bin/sleep","3"),1000)
  assertTrue(r.timedOut);assertNull(r.exitCode)
 }
 @Test fun boundsOutputWithoutBlockingProcess() {
  val r=CommandRunner().run(arrayOf("/bin/sh","-c","head -c 70000 /dev/zero"),1000)
  assertEquals(0,r.exitCode);assertEquals(65536,r.output.toByteArray().size);assertTrue(r.truncated)
 }
 @Test(expected=IllegalArgumentException::class) fun rejectsInvalidArgumentsBeforeStarting() {CommandRunner().run(arrayOf("bad\u0000argument"),1000)}
}
