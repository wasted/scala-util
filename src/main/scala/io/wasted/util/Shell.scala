package io.wasted.util

import java.io.{ File, BufferedReader, InputStreamReader }

/**
 * Current working directory
 * @param file File object representing the directory
 */
case class CWD(file: File)

/**
 * Companion for the current working directory
 */
object CWD {
  /**
   * Get a CWD for the given string path
   * @param path String path
   * @return Option of a CWD
   */
  def apply(path: String): Option[CWD] = {
    val file = new File(path)
    if (file.exists && file.isDirectory) Some(CWD(file)) else None
  }
}

/**
 * Result of a Shell Operation
 * @param lines Output lines from the operation
 * @param exitValue Explains itself
 */
case class ShellOperation(lines: Seq[String], exitValue: Int)

/**
 * Shell companion object
 */
object Shell {
  private[this] final val dummyLineFunc = (x: String) => x

  /**
   * Run the given cmd
   * @param cmd Command
   * @param cwd Current Working Directory
   * @return Shell Operation
   */
  def run(cmd: String)(implicit cwd: CWD): ShellOperation = run(cmd :: Nil, dummyLineFunc)

  /**
   * Run the given cmd with params
   * @param cmd Command
   * @param params Command Line Parameters
   * @param cwd Current Working Directory
   * @return Shell Operation
   */
  def run(cmd: String, params: String*)(implicit cwd: CWD): ShellOperation = run(Seq(cmd) ++ params, dummyLineFunc)

  /**
   * Run the given cmd with a Line-Function
   * @param cmds Command-Sequence
   * @param lineFunc Function to be used on every output-line
   * @param cwd Current Working Directory
   * @return Shell Operation
   */
  def run(cmds: Seq[String], lineFunc: (String) => Any = dummyLineFunc)(implicit cwd: CWD): ShellOperation = {
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

    process.waitFor()
    resultBuffer.close()
    ShellOperation(output, process.exitValue)
  }
}
