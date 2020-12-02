/* Copyright 2019-2020 EPFL, Lausanne */

package fit

import java.io.File

object Utils {
  def files(dir: String, pred: String => Boolean = _ => true): List[String] = {
    new File(dir).listFiles.toList.filter((f: File) => f.isFile && pred(f.getPath)).map(_.getPath)
  }
}
