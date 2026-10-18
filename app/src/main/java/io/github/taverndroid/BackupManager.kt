package io.github.taverndroid

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整包备份 / 导入。备份 zip 的内容（导入也认这个格式）：
 *   config.yaml       服务器配置（可选）
 *   data/…            数据根目录：所有用户（default-user/ 等）、角色、聊天、世界书、预设、设置、secrets.json
 *   plugins/…         服务端插件（可选）
 *   extensions/…      全局第三方扩展（可选）
 * 外面多包一层文件夹也可以（比如整个 SillyTavern/ 打包），这时
 * SillyTavern/public/scripts/extensions/third-party/ 也会当作全局扩展导入，其余程序文件忽略。
 * 只有一个用户文件夹（default-user/…）或者 SillyTavern 界面里下载的用户备份也能导入。
 *
 * 导入是「合并覆盖」：zip 里有的文件覆盖进来，zip 里没有的保留不动。
 * 本类不打印、不上传任何文件内容（包括 secrets.json）。
 */
class BackupManager(private val context: Context) {
    private val paths = StPaths(context)

    /** 写一个完整备份到 [out]。 */
    fun backup(out: OutputStream, progress: (String) -> Unit) {
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            addTree(zip, paths.data, "data", progress) { rel ->
                // 前端编译缓存、上传临时文件会自动重建，不打包
                !rel.startsWith("_webpack") && !rel.startsWith("_uploads")
            }
            if (paths.config.exists()) addFile(zip, paths.config, "config.yaml")
            addTree(zip, paths.plugins, "plugins", progress)
            addTree(zip, paths.extensions, "extensions", progress)
        }
    }

    fun backup(target: Uri, progress: (String) -> Unit) {
        context.contentResolver.openOutputStream(target)!!.use { backup(it, progress) }
    }

    /**
     * 备份到 下载/酒馆/（[auto] 为 true 时放 下载/酒馆/自动备份/，只留最近 [KEEP_AUTO] 个）。
     * 返回给用户看的位置。
     */
    fun backupToDownloads(label: String, auto: Boolean, progress: (String) -> Unit): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val folder = if (auto) "${FileSaver.FOLDER}/$AUTO_FOLDER" else FileSaver.FOLDER
        val name = "$label-$stamp.zip"
        val relative = Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录里创建文件")
        try {
            resolver.openOutputStream(uri)!!.use { backup(it, progress) }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        if (auto) pruneAutoBackups(relative)
        return "下载/$folder/$name"
    }

    /** 自动备份只留最近几个（只会看到、也只会删本 app 自己建的文件）。 */
    private fun pruneAutoBackups(relative: String) {
        runCatching {
            val resolver = context.contentResolver
            val ids = mutableListOf<Long>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(relative),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
            ids.drop(KEEP_AUTO).forEach { id ->
                resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
    }

    data class RestoreResult(val files: Int, val failed: Int)

    /**
     * 合并覆盖导入。必须在服务器停止时调用。
     * 普通数据文件逐个覆盖；扩展和插件按「整个文件夹」替换（zip 里带了哪个扩展，就用 zip 里那一份，
     * 免得新旧两份 git 仓库文件混在一起）；zip 里没有的文件和扩展都保留。
     */
    fun restore(source: Uri, progress: (String) -> Unit): RestoreResult {
        progress("正在检查压缩包…")
        val layout = detectLayout(source)
        var count = 0
        var failed = 0
        val allowedRoots = listOf(paths.plugins, paths.extensions, paths.data).map { it.canonicalPath }
        val config = paths.config.canonicalPath
        val replaced = HashSet<String>()
        context.contentResolver.openInputStream(source)!!.use { raw ->
            ZipInputStream(raw.buffered(1 shl 16)).use { zip ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = mapEntry(normalize(entry.name), layout) ?: continue
                    val canonical = target.canonicalPath
                    require(canonical == config || allowedRoots.any { canonical == it || canonical.startsWith(it + File.separator) }) {
                        "压缩包里有不安全的路径：${entry.name}"
                    }
                    // 第一次碰到某个扩展/插件文件夹时，先把 app 里的旧版本整个删掉（导入前已经备份过）
                    extensionFolder(target)?.let { folder ->
                        if (replaced.add(folder.absolutePath) && folder.exists()) Fs.deleteTree(folder)
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    val ok = runCatching {
                        target.parentFile?.mkdirs()
                        // git 的对象文件是只读的，直接覆盖会失败，先删掉
                        if (target.exists() && (!target.canWrite() || Fs.isLink(target))) Fs.deleteTree(target)
                        target.outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                        if (entry.time > 0) target.setLastModified(entry.time)
                    }.isSuccess
                    if (ok) count++ else {
                        failed++
                        ServerController.log.add("[导入] 这个文件没能写入：${entry.name}")
                    }
                    if ((count + failed) % 50 == 0) progress("已导入 $count 个文件…")
                }
            }
        }
        return RestoreResult(count, failed)
    }

    /**
     * 如果 [target] 在某个扩展/插件文件夹里，返回那个文件夹：
     *   st/plugins/<名字>/…、st/extensions/<名字>/…、st/data/<用户>/extensions/<名字>/…
     */
    private fun extensionFolder(target: File): File? {
        fun under(base: File, depth: Int): File? {
            val rel = target.relativeToOrNull(base)?.invariantSeparatorsPath ?: return null
            if (rel.isEmpty() || rel.startsWith("..")) return null
            val parts = rel.split('/')
            if (parts.size <= depth) return null // 文件夹本身或者顶层文件（比如 plugins/package.json）
            return File(base, parts.take(depth).joinToString("/"))
        }
        under(paths.plugins, 1)?.let { return it }
        under(paths.extensions, 1)?.let { return it }
        val rel = target.relativeToOrNull(paths.data)?.invariantSeparatorsPath ?: return null
        val parts = rel.split('/')
        if (parts.size >= 4 && parts[1] == "extensions" && !parts[0].startsWith("_")) {
            return File(paths.data, parts.take(3).joinToString("/"))
        }
        return null
    }

    private enum class Layout { FULL, USER_FOLDERS, USER_CONTENT }

    private fun normalize(name: String) = name.replace('\\', '/').trimStart('/')

    /**
     * 看 zip 里有什么，判断格式，并去掉最多一层外包文件夹。
     * 只记录判断要用的少量信息，所以就算 zip 里带了整个 node_modules 也不会占很多内存。
     */
    private fun detectLayout(source: Uri): Pair<Layout, String> {
        val dataPrefixes = LinkedHashSet<String>()
        val userPrefixes = LinkedHashSet<String>()
        val settingsPrefixes = LinkedHashSet<String>()
        val contentPrefixes = HashSet<String>()

        /** [name] 里第 0 层或第 1 层以 [segment] 开头的位置，返回它前面的外包前缀。 */
        fun prefixOf(name: String, segment: String): String? {
            if (name.startsWith(segment)) return ""
            val slash = name.indexOf('/')
            if (slash > 0 && name.startsWith(segment, slash + 1)) return name.substring(0, slash + 1)
            return null
        }

        context.contentResolver.openInputStream(source)!!.use { raw ->
            ZipInputStream(raw.buffered(1 shl 16)).use { zip ->
                while (true) {
                    val name = normalize(zip.nextEntry?.name ?: break)
                    prefixOf(name, "data/")?.let { dataPrefixes += it }
                    prefixOf(name, "default-user/")?.let { userPrefixes += it }
                    if (name.endsWith("settings.json") && name.count { it == '/' } <= 1) {
                        settingsPrefixes += name.removeSuffix("settings.json")
                    }
                    prefixOf(name, "chats/")?.let { contentPrefixes += it }
                    prefixOf(name, "characters/")?.let { contentPrefixes += it }
                }
            }
        }

        // 同时有好几种候选时，没有外包文件夹的优先
        fun pick(set: Set<String>) = if ("" in set) "" else set.firstOrNull()
        pick(dataPrefixes)?.let { return Layout.FULL to it }
        pick(userPrefixes)?.let { return Layout.USER_FOLDERS to it }
        // SillyTavern 界面里「下载备份」得到的：用户文件夹里的内容直接在最外层
        settingsPrefixes.firstOrNull { it in contentPrefixes }?.let { return Layout.USER_CONTENT to it }
        throw IllegalArgumentException("这个 zip 看起来不是酒馆 / SillyTavern 的数据包。请按 README 里的格式准备。")
    }

    private fun mapEntry(name: String, layout: Pair<Layout, String>): File? {
        val (kind, prefix) = layout
        if (!name.startsWith(prefix)) return null
        val rel = name.removePrefix(prefix)
        if (rel.isEmpty()) return null
        return when (kind) {
            Layout.FULL -> when {
                rel == "config.yaml" -> paths.config
                rel.startsWith("data/_webpack/") || rel.startsWith("data/_uploads/") -> null
                rel.startsWith("data/") -> File(paths.data, rel.removePrefix("data/"))
                rel.startsWith("plugins/") -> File(paths.plugins, rel.removePrefix("plugins/"))
                rel.startsWith("extensions/") -> File(paths.extensions, rel.removePrefix("extensions/"))
                rel.startsWith(ST_GLOBAL_EXTENSIONS) -> File(paths.extensions, rel.removePrefix(ST_GLOBAL_EXTENSIONS))
                else -> null
            }
            Layout.USER_FOLDERS -> File(paths.data, rel)
            Layout.USER_CONTENT -> File(paths.data, "default-user/$rel")
        }
    }

    private fun addTree(
        zip: ZipOutputStream, dir: File, prefix: String, progress: (String) -> Unit,
        include: (String) -> Boolean = { true },
    ) {
        if (!dir.exists()) return
        var count = 0
        dir.walkTopDown().onEnter { !Fs.isLink(it) || it == dir }.forEach { file ->
            val rel = file.relativeTo(dir).invariantSeparatorsPath
            if (rel.isEmpty() || !include(rel) || Fs.isLink(file)) return@forEach
            if (file.isDirectory) {
                zip.putNextEntry(ZipEntry("$prefix/$rel/"))
                zip.closeEntry()
            } else {
                addFile(zip, file, "$prefix/$rel")
                if (++count % 50 == 0) progress("已打包 $prefix 里的 $count 个文件…")
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        const val AUTO_FOLDER = "自动备份"
        const val KEEP_AUTO = 3
        private const val ST_GLOBAL_EXTENSIONS = "public/scripts/extensions/third-party/"
    }
}
