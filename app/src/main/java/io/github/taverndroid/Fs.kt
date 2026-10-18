package io.github.taverndroid

import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipInputStream

/**
 * 文件操作小工具。程序目录里有指向插件/扩展目录的符号链接，
 * Kotlin 自带的 deleteRecursively/copyRecursively 会顺着链接走进去，
 * 所以删除和复制程序目录一律用这里不跟随链接的版本。
 */
object Fs {
    fun isLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /** 删除整个目录树；遇到符号链接只删链接本身，绝不进入链接指向的目录。 */
    fun deleteTree(file: File) {
        val path = file.toPath()
        if (isLink(file)) {
            Files.deleteIfExists(path)
            return
        }
        if (!file.exists()) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(p)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(p: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(p)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 复制目录树；符号链接按原样重建成链接。 */
    fun copyTree(source: File, target: File) {
        val src = source.toPath()
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                File(target, src.relativize(dir).toString()).mkdirs()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dest = File(target, src.relativize(p).toString())
                if (attrs.isSymbolicLink) {
                    dest.delete()
                    Os.symlink(Os.readlink(p.toString()), dest.absolutePath)
                } else {
                    p.toFile().copyTo(dest, overwrite = true)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 目录大小（字节），不跟随链接。 */
    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(p: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }

    /**
     * 解压 zip 到 [target]。[stripFirst] 为 true 时去掉每个条目最外层的一级目录
     * （GitHub 下载的源码包都包了一层）。会拒绝跳出目标目录的条目。
     */
    fun unzip(input: InputStream, target: File, stripFirst: Boolean, onEntry: (Long) -> Unit = {}) {
        val base = target.canonicalPath + File.separator
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            val buffer = ByteArray(1 shl 16)
            var count = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                var name = entry.name.replace('\\', '/').trimStart('/')
                if (stripFirst) {
                    val slash = name.indexOf('/')
                    name = if (slash >= 0) name.substring(slash + 1) else ""
                }
                if (name.isEmpty()) continue
                val out = File(target, name)
                require(out.canonicalPath.startsWith(base)) { "压缩包里有不安全的路径：${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            o.write(buffer, 0, n)
                        }
                    }
                }
                onEntry(++count)
            }
        }
    }

    /** 比较 "1.19.0" 这类版本号；只看开头的数字部分。 */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = Regex("""^\D*(\d+(?:\.\d+)*)""").find(v)?.groupValues?.get(1)
            ?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
