package dev.plume

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/** Text put in a phone page's HTML. */
internal fun pageHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Text put in a single-quoted string of a phone page's script. */
internal fun pageJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c").replace("\n", " ")

/**
 * The small web server behind the pages a phone opens from a QR code on the TV. It only runs while its panel is
 * open, and only answers under /<code> (the random code is in the QR code), so others on the Wi-Fi can't use it.
 * One request at a time, on its own thread. A subclass calls [start] once it is ready to answer.
 */
internal abstract class PhoneServer(val code: String, private val threadName: String) {
    private val socket = try {
        ServerSocket(8080)
    } catch (e: IOException) {
        ServerSocket(0) // 8080 taken: any free port (it's in the QR code either way)
    }
    val port get() = socket.localPort
    @Volatile private var client: Socket? = null

    /** What a request outside /<code> is told. */
    protected abstract val notFound: String

    /** Answers one request; [sub] is the path after /<code> ("" for the page itself). Runs on the server thread. */
    protected abstract fun route(method: String, sub: String, headers: Map<String, String>, input: InputStream, out: OutputStream)

    /** After each request, answered or failed. */
    protected open fun done() {}

    protected fun start() {
        Thread(::serve, threadName).start()
    }

    fun stop() { socket.close(); client?.close() }

    private fun serve() {
        while (!socket.isClosed) {
            val s = try {
                socket.accept()
            } catch (e: IOException) {
                return // closed
            }
            client = s
            try {
                if (socket.isClosed) s.close()
                s.use { handle(it) }
            } catch (e: Exception) {
                android.util.Log.w("Plume", "$threadName request failed ($e)")
            } finally {
                client = null
                done()
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val request = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val method = request.substringBefore(' ')
        val path = request.substringAfter(' ').substringBefore(' ').substringBefore('?')
        if (path != "/$code" && !path.startsWith("/$code/")) return reply(out, 404, "text/plain; charset=utf-8", notFound)
        route(method, path.removePrefix("/$code"), headers, input, out)
    }

    /** Copies a request body of [length] bytes to [sink]. */
    protected fun readBody(input: InputStream, length: Long, sink: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("upload cut short")
            sink.write(buf, 0, n)
            left -= n
        }
    }

    /** One header line, without the line break; null at the end of the stream. Capped so junk can't fill memory. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw IOException("header line too long")
    }

    protected fun reply(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}

/** What the upload page and its answers say, in the TV's language ([added] takes the wallpaper's name, [tooBig] the limit in MB). */
internal class UploadTexts(
    val title: String, val heading: String, val intro: String, val choose: String, val send: String, val sending: String,
    val another: String, val unreachable: String, val added: (String) -> String, val badVideo: String, val badPhoto: String,
    val tooBig: (Int) -> String, val notFound: String,
)

/**
 * The page for adding wallpapers from a phone. It sends the photo as the raw request body, so there's no multipart parsing.
 * [onUpload] runs on the server thread with the uploaded file and whether it is a video, and returns the new wallpaper's name, or null.
 */
internal class UploadServer(
    code: String, private val temp: File, private val texts: UploadTexts, private val onUpload: (File, Boolean) -> String?,
) : PhoneServer(code, "wallpaper-upload") {
    override val notFound get() = texts.notFound

    init {
        start()
    }

    override fun done() {
        temp.delete()
    }

    override fun route(method: String, sub: String, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        when {
            sub != "" -> reply(out, 404, "text/plain; charset=utf-8", texts.notFound)
            method == "GET" -> reply(out, 200, "text/html; charset=utf-8", page(texts))
            method == "POST" -> {
                val length = headers["content-length"]?.toLongOrNull() ?: -1
                val video = headers["content-type"].orEmpty().startsWith("video/") // the browser sends the file's own type
                val max = if (video) VIDEO_LIMIT else MAX_BYTES
                if (length !in 1..max) return reply(out, 413, "text/plain; charset=utf-8", texts.tooBig((max / 1024 / 1024).toInt()))
                temp.outputStream().use { readBody(input, length, it) }
                val name = try {
                    onUpload(temp, video)
                } finally {
                    temp.delete()
                }
                if (name != null) reply(out, 200, "text/plain; charset=utf-8", texts.added(name))
                else reply(out, 415, "text/plain; charset=utf-8", if (video) texts.badVideo else texts.badPhoto)
            }
            else -> reply(out, 405, "text/plain", "Not allowed")
        }
    }

    private companion object {
        const val MAX_BYTES = 25L * 1024 * 1024

        fun page(t: UploadTexts) = """
            <!doctype html>
            <html dir="auto"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${pageHtml(t.title)}</title>
            <style>
              body{font:17px system-ui,sans-serif;margin:0;padding:32px 20px;background:#f7f8fa;color:#111214}
              @media (prefers-color-scheme:dark){body{background:#09090b;color:#f5f5f7}}
              h1{font-size:24px;margin:0 0 8px} p{opacity:.7;margin:0 0 24px}
              label,button{display:block;width:100%;box-sizing:border-box;padding:16px;border-radius:12px;font:inherit;text-align:center;margin-bottom:12px}
              label{border:1.5px dashed currentColor;cursor:pointer} button{border:0;background:#111214;color:#fff}
              @media (prefers-color-scheme:dark){button{background:#f5f5f7;color:#111214}}
              button:disabled{opacity:.35} input{display:none} #s{min-height:1.5em}
            </style></head><body>
            <h1>${pageHtml(t.heading)}</h1>
            <p>${pageHtml(t.intro)}</p>
            <label><input id="f" type="file" accept="image/*,video/mp4"><span id="n">${pageHtml(t.choose)}</span></label>
            <button id="b" disabled>${pageHtml(t.send)}</button>
            <div id="s"></div>
            <script>
              const f=document.getElementById('f'),b=document.getElementById('b'),s=document.getElementById('s'),n=document.getElementById('n');
              f.onchange=()=>{b.disabled=!f.files.length;n.textContent=f.files.length?f.files[0].name:'${pageJs(t.choose)}';s.textContent=''};
              b.onclick=async()=>{
                b.disabled=true;s.textContent='${pageJs(t.sending)}';
                try{const r=await fetch(location.pathname,{method:'POST',body:f.files[0]});
                  s.textContent=(await r.text())+(r.ok?' ${pageJs(t.another)}':'');}
                catch(e){s.textContent='${pageJs(t.unreachable)}';}
                b.disabled=false;
              };
            </script></body></html>
        """.trimIndent()
    }
}

/**
 * Turns a photo into a wallpaper: upright (phones often store photos sideways with an orientation tag),
 * cropped to 16:9 around the centre, 1920×1080, mutable. [open] is called once per pass over the image.
 * Returns null if it isn't an image Android can decode.
 */
internal fun fitWallpaper(open: () -> InputStream): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val rotation = try {
        when (open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }
    val (w, h) = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
    // Decode at the smallest size whose 16:9 crop still covers the screen: a 12 MP photo in full is ~48 MB.
    val cropW = minOf(w, h * 16 / 9)
    var sample = 1
    while (cropW / (sample * 2) >= WALL_W) sample *= 2
    val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val upright = if (rotation == 0) decoded else
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { decoded.recycle() }
    val cw = minOf(upright.width, upright.height * 16 / 9)
    val ch = cw * 9 / 16
    val left = (upright.width - cw) / 2
    val top = (upright.height - ch) / 2
    val out = Bitmap.createBitmap(WALL_W, WALL_H, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(upright, Rect(left, top, left + cw, top + ch), Rect(0, 0, WALL_W, WALL_H), Paint(Paint.FILTER_BITMAP_FLAG))
    upright.recycle()
    return out
}

/**
 * Decodes a photo preserving its full original aspect ratio (uncropped).
 * Upright (handles EXIF orientation), downsampled if larger than 3840px.
 */
internal fun decodeUncroppedWallpaper(open: () -> InputStream): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val rotation = try {
        open().use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    } catch (e: Exception) {
        0
    }
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (maxDim / (sample * 2) >= 3840) sample *= 2
    val decoded = open().use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    return if (rotation == 0) decoded else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
}

/** Saves a phone photo as a wallpaper file without cropping it. Returns the file, or null if it isn't an image. */
internal fun importWallpaper(src: File, dir: File): File? {
    val out = decodeUncroppedWallpaper { src.inputStream() } ?: return null
    dir.mkdirs()
    val file = generateSequence(1) { it + 1 }.map { File(dir, "Photo $it.jpg") }.first { !it.exists() }
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    out.recycle()
    return file
}

internal fun importVideoWallpaper(src: File, dir: File): File? {
    if (!validVideo(src)) return null
    dir.mkdirs()
    val file = generateSequence(1) { it + 1 }.map { File(dir, "Video $it.mp4") }.first { !it.exists() }
    src.copyTo(file)
    return file
}

private const val WALL_W = 1920
private const val WALL_H = 1080
