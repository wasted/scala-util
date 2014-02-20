package io.wasted.util

import java.io.{ File, BufferedReader, InputStreamReader }

case class CWD(file: File)
object CWD {
  def apply(path: String): Option[CWD] = {
    val file = new File(path)
    if (file.exists && file.isDirectory) Some(CWD(file)) else None
  }
}

case class ShellOperation(lines: Seq[String], exitValue: Int)

object Shell {
  private[this] final val dummyLineFunc = (x: String) => x

  def run(cmds: String*)(implicit cwd: CWD): ShellOperation = exec(cmds, dummyLineFunc)
  def run(cmds: Seq[String], lineFunc: (String) => Any)(implicit cwd: CWD): ShellOperation = exec(cmds, dummyLineFunc)
  def exec(cmds: Seq[String], lineFunc: (String) => Any)(implicit cwd: CWD): ShellOperation = {
    var output = Seq[String]()
    val process = Runtime.getRuntime.exec(cmds.toArray, null, cwd.file)
    val resultBuffer = new BufferedReader(new InputStreamReader(process.getInputStream))

    // Parse the output from rsync line-based and send it to the actor
    var line: String = null
    do {
      line = resultBuffer.readLine
      output ++= List(line)
      lineFunc(line)
    } while (line != null)

    process.waitFor
    resultBuffer.close
    ShellOperation(output, process.exitValue)
  }
}
