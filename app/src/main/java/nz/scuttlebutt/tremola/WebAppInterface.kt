package nz.scuttlebutt.tremola

import android.app.Activity
import android.app.Instrumentation
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat.checkSelfPermission
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject

import nz.scuttlebutt.tremola.ssb.TremolaState
import nz.scuttlebutt.tremola.ssb.db.entities.LogEntry
import nz.scuttlebutt.tremola.ssb.db.entities.Pub
import nz.scuttlebutt.tremola.ssb.peering.RpcInitiator
import nz.scuttlebutt.tremola.ssb.peering.RpcServices
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.jar.Manifest


// pt 3 in https://betterprogramming.pub/5-android-webview-secrets-you-probably-didnt-know-b23f8a8b5a0c

class WebAppInterface(val act: Activity, val tremolaState: TremolaState, val webView: WebView) {

    private var recorder: MediaRecorder? = null

    @JavascriptInterface
    fun onFrontendRequest(s: String) {
        //handle the data captured from webview}
        Log.d("FrontendRequest", s)
        val args = s.split(" ")
        when (args[0]) {
            "onBackPressed" -> {
                (act as MainActivity)._onBackPressed()
            }
            "ready" -> {
                eval("b2f_initialize(\"${tremolaState.idStore.identity.toRef()}\")")
            }
            "reset" -> { // UI reset
                // erase DB content
                eval("b2f_initialize(\"${tremolaState.idStore.identity.toRef()}\")")
            }
            "restream" -> {
                for (e in tremolaState.logDAO.getAllAsList())
                    if (e.pri != null) // only private chat msgs
                        sendEventToFrontend(e)
            }
            "qrscan.init" -> {
                val intentIntegrator = IntentIntegrator(act)
                intentIntegrator.setBeepEnabled(false)
                intentIntegrator.setCameraId(0)
                intentIntegrator.setPrompt("SCAN")
                intentIntegrator.setBarcodeImageEnabled(false)
                intentIntegrator.initiateScan()
                return
            }
            "secret:" -> {
                if (importIdentity(args[1])) {
                    tremolaState.logDAO.wipe()
                    tremolaState.contactDAO.wipe()
                    tremolaState.pubDAO.wipe()
                    act.finishAffinity()
                }
                return
            }
            "exportSecret" -> {
                val json = tremolaState.idStore.identity.toExportString()!!
                eval("b2f_showSecret('${json}');")
                val clipboard = tremolaState.context.getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newPlainText("simple text", json)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(act, "secret key was also\ncopied to clipboard",
                    Toast.LENGTH_LONG).show()
            }
            "sync" -> {
                addPub(args[1])
                return
            }
            "wipe" -> {
                tremolaState.logDAO.wipe()
                tremolaState.contactDAO.wipe()
                tremolaState.pubDAO.wipe()
                tremolaState.idStore.setNewIdentity(null) // creates new identity
                // eval("b2f_initialize(\"${tremolaState.idStore.identity.toRef()}\")")
                // FIXME: should kill all active connections, or better then the app
                act.finishAffinity()
            }
            "add:contact" -> { // ID and alias
                tremolaState.addContact(args[1],
                    Base64.decode(args[2], Base64.NO_WRAP).decodeToString())
                val rawStr = tremolaState.msgTypes.mkFollow(args[1])
                val evnt = tremolaState.msgTypes.jsonToLogEntry(rawStr,
                    rawStr.encodeToByteArray())
                evnt?.let {
                    rx_event(it) // persist it, propagate horizontally and also up
                    tremolaState.peers.newContact(args[1]) // inform online peers via EBT
                }
                    return
            }
            "priv:post" -> { // atob(text) rcp1 rcp2 ...
                val rawStr = tremolaState.msgTypes.mkPost(
                                 Base64.decode(args[1], Base64.NO_WRAP).decodeToString(),
                                 args.slice(2..args.lastIndex))
                Log.d("onFrontendRequest", "Message: " + args)
                val evnt = tremolaState.msgTypes.jsonToLogEntry(rawStr,
                                            rawStr.encodeToByteArray())
                evnt?.let { rx_event(it) } // persist it, propagate horizontally and also up
                return
            }
            "get:file" -> {
                chooseImageGallery();
            }
            "invite:redeem" -> {
                try {
                    val i = args[1].split("~")
                    val h = i[0].split(":")
                    val remoteKey = Base64.decode(h[2].slice(1..-8), Base64.NO_WRAP)
                    val seed = Base64.decode(i[1], Base64.NO_WRAP)
                    val rpcStream = RpcInitiator(tremolaState, remoteKey)
                    val ex = Executors.newSingleThreadExecutor() // one thread per peer
                    ex?.execute {
                        rpcStream.defineServices(RpcServices(tremolaState))
                        rpcStream.startPeering(h[0], h[1].toInt(), seed)
                    }
                    Toast.makeText(act, "Pub is being contacted ..",
                        Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(act, "Problem parsing invite code",
                        Toast.LENGTH_LONG).show()
                }
            }
            "make:image" -> {
                // Make an image for immediate sending
                Log.d("onFrontendRequest", "Trying to take image")
                takeImage()
            }
            "start:recording" -> {
                if (!(act as MainActivity).permissionToRecordAccepted) {
                    // Request permission if the app doesn't have permission
                    Log.d("audio", "Request Permission")
                    ActivityCompat.requestPermissions((act as MainActivity), (act as MainActivity).permissions, 200)
                } else {
                    // Start Recording
                    Log.d("audio", "Trying to start recording")

                    // Setting up the Mediarecorder
                    var path = act.cacheDir.toString() + "/tremolaAudio.mp3"
                    recorder = MediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setOutputFile("$path")
                    }

                    // Start recording
                    recorder!!.prepare()
                    recorder!!.start()
                    eval("audioStatus(1)")
                }
            }
            "stop:recording" -> {
                // Stop Recording only if the permission to record was accepted
                if ((act as MainActivity).permissionToRecordAccepted) {
                    // Stop recording
                    Log.d("audio", "Trying to stop recording")
                    try {
                        recorder!!.stop()
                        recorder!!.release()
                    } catch (e: Exception) {
                        // Maybe happen if the button is pressed again to fast
                        Log.e("audio", "Error stopping recording")
                        // Reset everything
                        recorder = null;
                        eval("audioStatus(0)")
                        return
                    }
                    eval("audioStatus(2)")

                    var path = act.cacheDir.toString() + "/tremolaAudio.mp3"
                    val data: String = convertAudioFileToBase64(path)
                    // TODO: Audio Compression improvements

                    // Send the audio file to the peer
                    eval("sendAudio('${data}')")

                    // Delete File for cleanup
                    val myFile: File = File(path)
                    myFile.delete()
                    eval("audioStatus(0)")
                }
            }
            "debug" -> {
                // Debug message to debug JS Code
                Log.d("jsFrontend", args.toString())
            }
            else -> {
                Log.d("onFrontendRequest", "unknown")
            }
        }
        /*
        if (s == "btn:chats") {
            select(listOf("chats","contacts","profile"))
        }
        if (s == "btn:contacts") {
            select(listOf("contacts","chats","profile"))
        }
        if (s == "btn:profile") {
            select(listOf("profile","contacts","chats"))
        }
        */
    }

    private fun takeImage() {
        // Tries to make an image with the camera app
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            act.startActivityForResult(takePictureIntent, 1)
        } catch (e: ActivityNotFoundException) {
            Log.e("Image", "Error while taking the image")
        }
    }

    fun eval(js: String) { // send JS string to webkit frontend for execution
        webView.post(Runnable {
            webView.evaluateJavascript(js, null)
        })
    }

    private fun convertAudioFileToBase64(path: String): String {
        val baos = ByteArrayOutputStream()
        val fis = FileInputStream(File(path))
        var data: ByteArray = ByteArray(1024)
        var audioBytes: ByteArray

        // Convert Audio file to bytes
        var n: Int
        while (-1 != fis.read(data).also { n = it }) baos.write(data, 0, n)
        audioBytes = baos.toByteArray()

        return Base64.encodeToString(audioBytes, Base64.NO_WRAP);
    }

    private fun importIdentity(secret: String): Boolean {
        Log.d("D/importIdentity", secret)
        if (tremolaState.idStore.setNewIdentity(Base64.decode(secret, Base64.DEFAULT))) {
            // FIXME: remove all decrypted content in the database, try to decode new one
            Toast.makeText(act, "Imported of ID worked. You must restart the app.",
                Toast.LENGTH_SHORT).show()
            return true
        }
        Toast.makeText(act, "Import of new ID failed.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun addPub(pubstring: String) {
        Log.d("D/addPub", pubstring)
        val components = pubstring.split(":")
        tremolaState.addPub(
            Pub(lid = "@" + components[3] + ".ed25519",
                host = components[1],
                port = components[2].split('~')[0].toInt())
        )
    }

    fun rx_event(entry: LogEntry) {
        // when we come here we assume that the event is legit (chaining and signature)
        tremolaState.addLogEntry(entry)       // persist the log entry
        sendEventToFrontend(entry)            // notify the local app
        tremolaState.peers.newLogEntry(entry) // stream it to peers we are currently connected to
    }

    fun chooseImageGallery() {

        val IMAGE_CHOOSE = 1111;
        val iintent = Intent(Intent.ACTION_PICK)
        iintent.type = "image/*"
        act.startActivityForResult(iintent, IMAGE_CHOOSE)
    }

    fun sendEventToFrontend(evnt: LogEntry) {
        // Log.d("MSG added", evnt.ref.toString())
        var hdr = JSONObject()
        hdr.put("ref", evnt.hid)
        hdr.put("fid", evnt.lid)
        hdr.put("seq", evnt.lsq)
        hdr.put("pre", evnt.pre)
        hdr.put("tst", evnt.tst)
        var cmd = "b2f_new_event({header:${hdr.toString()},"
        cmd += "public:" + (if (evnt.pub == null) "null" else evnt.pub) + ","
        cmd += "confid:" + (if (evnt.pri == null) "null" else evnt.pri)
        cmd += "});"
        Log.d("CMD", cmd)
        eval(cmd)
    }
}