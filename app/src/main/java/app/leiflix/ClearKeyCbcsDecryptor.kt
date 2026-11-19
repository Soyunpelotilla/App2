@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Descriptor
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor
import androidx.media3.exoplayer.source.chunk.ChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Software decryptor for ClearKey CBCS (AES-128-CBC + pattern encryption) streams.
 *
 * Android's hardware ClearKey DRM HAL (android.hardware.drm@1.3-service.clearkey) only supports
 * CENC (AES-CTR) and rejects CBCS (MODE_AES_CBC) with ERROR_DRM_CANNOT_HANDLE (-2006).
 * This wrapper intercepts CBCS samples at the ExtractorOutput level, decrypts them in software
 * using the provided ClearKey KID->KEY map, strips drmInitData from Format and Manifest, and passes clear
 * samples to ExoPlayer's SampleQueue / MediaCodec.
 */
object ClearKeyCbcsDecryptor {

    private const val TAG = "CbcsDecryptor"

    /**
     * Custom DashManifestParser that strips ContentProtection and DrmInitData ONLY for CBCS streams,
     * allowing standard CENC streams to retain DRM metadata so Android's native ClearKey CDM
     * can handle them normally.
     */
    class CbcsDashManifestParser : DashManifestParser() {
        private var isManifestCbcs = false

        override fun parseContentProtection(xpp: XmlPullParser): android.util.Pair<String?, DrmInitData.SchemeData?> {
            val schemeIdUri = xpp.getAttributeValue(null, "schemeIdUri")
            val value = xpp.getAttributeValue(null, "value")

            if (value?.equals("cbcs", ignoreCase = true) == true) {
                isManifestCbcs = true
            }

            // If this element or manifest is CBCS, strip it so ExoPlayer doesn't reject CBCS before extraction
            if (value?.equals("cbcs", ignoreCase = true) == true || (isManifestCbcs && value == null)) {
                Log.d(TAG, "Manifest ContentProtection (CBCS) encountered: schemeIdUri=$schemeIdUri, value=$value -> stripping")
                while (xpp.next() != XmlPullParser.END_TAG) {
                    // skip children
                }
                return android.util.Pair(null, null)
            }

            // For CENC (or non-CBCS), let default parser retain standard DRM scheme data
            return super.parseContentProtection(xpp)
        }

        override fun buildRepresentation(
            representationInfo: RepresentationInfo,
            extraContentLanguage: String?,
            extraLabels: List<Label>,
            extraSecurityProfile: String?,
            extraDrmSchemeDatas: ArrayList<DrmInitData.SchemeData>,
            inbandEventStreams: ArrayList<Descriptor>
        ): Representation {
            val isCbcs = isManifestCbcs ||
                    representationInfo.drmSchemeType?.equals("cbcs", ignoreCase = true) == true ||
                    representationInfo.format.drmInitData?.schemeType?.equals("cbcs", ignoreCase = true) == true

            if (isCbcs) {
                // Strip DRM data for CBCS representation
                extraDrmSchemeDatas.clear()
                val cleanFormat = representationInfo.format.buildUpon()
                    .setDrmInitData(null)
                    .setCryptoType(C.CRYPTO_TYPE_NONE)
                    .build()

                val cleanInfo = RepresentationInfo(
                    cleanFormat,
                    representationInfo.baseUrls,
                    representationInfo.segmentBase,
                    null, // drmSchemeType = null
                    ArrayList(), // drmSchemeDatas = empty
                    representationInfo.inbandEventStreams,
                    representationInfo.essentialProperties,
                    representationInfo.supplementalProperties,
                    representationInfo.revisionId
                )

                return super.buildRepresentation(
                    cleanInfo,
                    extraContentLanguage,
                    extraLabels,
                    extraSecurityProfile,
                    extraDrmSchemeDatas,
                    inbandEventStreams
                )
            }

            // For standard CENC, build Representation normally with DRM intact
            return super.buildRepresentation(
                representationInfo,
                extraContentLanguage,
                extraLabels,
                extraSecurityProfile,
                extraDrmSchemeDatas,
                inbandEventStreams
            )
        }
    }

    fun wrapDrmSessionManager(delegate: DrmSessionManager): DrmSessionManager {
        return object : DrmSessionManager {
            override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {
                delegate.setPlayer(playbackLooper, playerId)
            }

            override fun prepare() {
                delegate.prepare()
            }

            override fun release() {
                delegate.release()
            }

            override fun preacquireSession(
                eventDispatcher: DrmSessionEventListener.EventDispatcher?,
                format: Format
            ): DrmSessionManager.DrmSessionReference {
                if (format.drmInitData?.schemeType == "cbcs" || format.drmInitData == null) {
                    return DrmSessionManager.DrmSessionReference.EMPTY
                }
                return delegate.preacquireSession(eventDispatcher, format)
            }

            override fun acquireSession(
                eventDispatcher: DrmSessionEventListener.EventDispatcher?,
                format: Format
            ): DrmSession? {
                if (format.drmInitData?.schemeType == "cbcs" || format.drmInitData == null) {
                    return null
                }
                return delegate.acquireSession(eventDispatcher, format)
            }

            override fun getCryptoType(format: Format): Int {
                if (format.drmInitData?.schemeType == "cbcs" || format.drmInitData == null) {
                    return C.CRYPTO_TYPE_NONE
                }
                return delegate.getCryptoType(format)
            }
        }
    }

    fun createDashChunkSourceFactory(
        dataSourceFactory: androidx.media3.datasource.DataSource.Factory,
        drmPairs: List<Pair<String, String>>
    ): DefaultDashChunkSource.Factory {
        val keyMap = HashMap<String, ByteArray>()
        for ((kid, key) in drmPairs) {
            val cleanKid = kid.replace("-", "").lowercase().trim()
            val cleanKey = key.replace("-", "").lowercase().trim()
            if (cleanKid.length == 32 && cleanKey.length == 32) {
                keyMap[cleanKid] = hexToBytes(cleanKey)
                Log.d(TAG, "Registered ClearKey CBCS: $cleanKid -> $cleanKey")
            }
        }

        val chunkExtractorFactory = ChunkExtractor.Factory {
                primaryTrackType: Int,
                representationFormat: Format,
                enableEventMessageTrack: Boolean,
                closedCaptionFormats: List<Format>,
                playerEmsgTrackOutput: TrackOutput?,
                _: PlayerId ->
            Log.d(TAG, "Creating ChunkExtractor for trackType=$primaryTrackType, format=${representationFormat.sampleMimeType}, drmScheme=${representationFormat.drmInitData?.schemeType}")
            var flags = FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS
            if (enableEventMessageTrack) {
                flags = flags or FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK
            }
            // Do not set codecsToParseWithinGopSampleDependencies so FragmentedMp4Extractor
            // does not inspect encrypted SEI/NAL payloads before our TrackOutput decrypts them.
            val fmp4Extractor = FragmentedMp4Extractor(
                SubtitleParser.Factory.UNSUPPORTED,
                flags,
                null,
                null,
                closedCaptionFormats,
                playerEmsgTrackOutput
            )
            val wrappedExtractor = CbcsDecryptingExtractor(fmp4Extractor, keyMap)
            val cleanManifestFormat = stripCbcsDrmFromFormat(representationFormat)
            BundledChunkExtractor(wrappedExtractor, primaryTrackType, cleanManifestFormat)
        }

        return DefaultDashChunkSource.Factory(chunkExtractorFactory, dataSourceFactory, 1)
    }

    fun stripCbcsDrmFromFormat(format: Format): Format {
        val drm = format.drmInitData ?: return format
        Log.d(TAG, "stripCbcsDrmFromFormat: schemeType=${drm.schemeType}")
        if (drm.schemeType == "cbcs") {
            return format.buildUpon()
                .setDrmInitData(null)
                .setCryptoType(C.CRYPTO_TYPE_NONE)
                .build()
        }
        return format
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private class CbcsDecryptingExtractor(
        private val delegate: Extractor,
        private val keyMap: Map<String, ByteArray>
    ) : Extractor {

        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

        override fun init(output: ExtractorOutput) {
            delegate.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    return CbcsDecryptingTrackOutput(output.track(id, type), keyMap)
                }

                override fun endTracks() {
                    output.endTracks()
                }

                override fun seekMap(seekMap: SeekMap) {
                    output.seekMap(seekMap)
                }
            })
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            return delegate.read(input, seekPosition)
        }

        override fun seek(position: Long, timeUs: Long) {
            delegate.seek(position, timeUs)
        }

        override fun release() {
            delegate.release()
        }
    }

    private class CbcsDecryptingTrackOutput(
        private val downstream: TrackOutput,
        private val keyMap: Map<String, ByteArray>
    ) : TrackOutput {

        private var isCbcsTrack = false
        private val buffer = ByteArrayOutputStream(65536)

        override fun format(format: Format) {
            val scheme = format.drmInitData?.schemeType
            Log.d(TAG, "format called: mime=${format.sampleMimeType}, schemeType=$scheme, drmInitData=${format.drmInitData != null}, keyMapSize=${keyMap.size}")
            if (scheme == "cbcs") {
                isCbcsTrack = true
                downstream.format(
                    format.buildUpon()
                        .setDrmInitData(null)
                        .setCryptoType(C.CRYPTO_TYPE_NONE)
                        .build()
                )
            } else {
                isCbcsTrack = false
                downstream.format(format)
            }
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            if (!isCbcsTrack) {
                return downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)
            }
            val temp = ByteArray(length)
            val bytesRead = input.read(temp, 0, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            buffer.write(temp, 0, bytesRead)
            return bytesRead
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!isCbcsTrack) {
                downstream.sampleData(data, length, sampleDataPart)
                return
            }
            val temp = ByteArray(length)
            data.readBytes(temp, 0, length)
            buffer.write(temp, 0, length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            if (!isCbcsTrack) {
                downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                return
            }

            val allBytes = buffer.toByteArray()
            buffer.reset()

            val sampleEnd = allBytes.size - offset
            val sampleStart = (sampleEnd - size).coerceAtLeast(0)
            val rawSample = if (sampleStart == 0 && sampleEnd == allBytes.size) {
                allBytes
            } else {
                allBytes.copyOfRange(sampleStart, sampleEnd)
            }

            val isEncrypted = (flags and C.BUFFER_FLAG_ENCRYPTED) != 0 && cryptoData != null
            if (!isEncrypted || cryptoData == null || rawSample.isEmpty()) {
                val outArray = ParsableByteArray(rawSample)
                downstream.sampleData(outArray, rawSample.size)
                val cleanFlags = flags and C.BUFFER_FLAG_ENCRYPTED.inv()
                downstream.sampleMetadata(timeUs, cleanFlags, rawSample.size, 0, null)
                return
            }

            Log.d(TAG, "sampleMetadata encrypted: timeUs=$timeUs, size=$size, cryptoMode=${cryptoData.cryptoMode}, encBlocks=${cryptoData.encryptedBlocks}, clearBlocks=${cryptoData.clearBlocks}")

            // Parse ExoPlayer's inline encryption header (written by TrackBundle.outputSampleEncryptionData):
            // 1 byte: signalByte (bit 7 = hasSubsamples, bits 0..6 = ivSize)
            // ivSize bytes: IV
            // if (hasSubsamples):
            //   2 bytes: subsampleCount (unsigned short)
            //   subsampleCount * 6 bytes: (2 bytes clearSize unsigned short + 4 bytes encryptedSize int)
            var pos = 0
            val signalByte = rawSample[pos++].toInt() and 0xFF
            val hasSubsamples = (signalByte and 0x80) != 0
            val ivSize = signalByte and 0x7F

            val iv = ByteArray(16)
            if (ivSize > 0 && pos + ivSize <= rawSample.size) {
                System.arraycopy(rawSample, pos, iv, 0, ivSize.coerceAtMost(16))
                pos += ivSize
            }

            val subsampleCount: Int
            val clearSizes: IntArray
            val encryptedSizes: IntArray

            if (hasSubsamples && pos + 2 <= rawSample.size) {
                subsampleCount = ((rawSample[pos].toInt() and 0xFF) shl 8) or (rawSample[pos + 1].toInt() and 0xFF)
                pos += 2
                clearSizes = IntArray(subsampleCount)
                encryptedSizes = IntArray(subsampleCount)
                for (i in 0 until subsampleCount) {
                    if (pos + 6 <= rawSample.size) {
                        clearSizes[i] = ((rawSample[pos].toInt() and 0xFF) shl 8) or (rawSample[pos + 1].toInt() and 0xFF)
                        encryptedSizes[i] = ((rawSample[pos + 2].toInt() and 0xFF) shl 24) or
                                ((rawSample[pos + 3].toInt() and 0xFF) shl 16) or
                                ((rawSample[pos + 4].toInt() and 0xFF) shl 8) or
                                (rawSample[pos + 5].toInt() and 0xFF)
                        pos += 6
                    }
                }
            } else {
                subsampleCount = 1
                clearSizes = intArrayOf(0)
                encryptedSizes = intArrayOf(rawSample.size - pos)
            }

            val payloadSize = (rawSample.size - pos).coerceAtLeast(0)
            val payload = ByteArray(payloadSize)
            if (payloadSize > 0) {
                System.arraycopy(rawSample, pos, payload, 0, payloadSize)
            }

            val kidHex = bytesToHex(cryptoData.encryptionKey)
            val keyBytes = keyMap[kidHex] ?: keyMap.values.firstOrNull()

            if (keyBytes != null && payloadSize > 0) {
                decryptCbcsPayload(
                    payload = payload,
                    keyBytes = keyBytes,
                    iv = iv,
                    subsampleCount = subsampleCount,
                    clearSizes = clearSizes,
                    encryptedSizes = encryptedSizes,
                    cryptByteBlock = cryptoData.encryptedBlocks,
                    skipByteBlock = cryptoData.clearBlocks
                )
            }

            val outArray = ParsableByteArray(payload)
            downstream.sampleData(outArray, payload.size)
            val cleanFlags = flags and C.BUFFER_FLAG_ENCRYPTED.inv()
            downstream.sampleMetadata(timeUs, cleanFlags, payload.size, 0, null)
        }

        private fun decryptCbcsPayload(
            payload: ByteArray,
            keyBytes: ByteArray,
            iv: ByteArray,
            subsampleCount: Int,
            clearSizes: IntArray,
            encryptedSizes: IntArray,
            cryptByteBlock: Int,
            skipByteBlock: Int
        ) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(iv)

                var offset = 0
                for (i in 0 until subsampleCount) {
                    offset += clearSizes[i]
                    val encBytes = encryptedSizes[i]
                    if (encBytes <= 0 || offset >= payload.size) {
                        offset += encBytes
                        continue
                    }

                    // ISO/IEC 23001-7 CBCS: IV is reinitialized at the start of each subsample
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                    if (cryptByteBlock == 0 && skipByteBlock == 0) {
                        // Full-subsample CBC encryption (0:0 pattern, typical for CBCS audio)
                        val blocksToDecrypt = (encBytes / 16) * 16
                        if (blocksToDecrypt > 0 && offset + blocksToDecrypt <= payload.size) {
                            val decrypted = cipher.update(payload, offset, blocksToDecrypt)
                            if (decrypted != null) {
                                System.arraycopy(decrypted, 0, payload, offset, decrypted.size)
                            }
                        }
                    } else {
                        // Pattern encryption (e.g. 1:9 pattern for CBCS video)
                        val cryptLen = cryptByteBlock * 16
                        val skipLen = skipByteBlock * 16
                        var rem = encBytes
                        var subPos = offset
                        while (rem >= 16 && subPos + 16 <= payload.size) {
                            val toCrypt = minOf(cryptLen, (rem / 16) * 16)
                            if (toCrypt > 0 && subPos + toCrypt <= payload.size) {
                                val decrypted = cipher.update(payload, subPos, toCrypt)
                                if (decrypted != null) {
                                    System.arraycopy(decrypted, 0, payload, subPos, decrypted.size)
                                }
                            }
                            val step = cryptLen + skipLen
                            if (step <= 0 || rem <= step) break
                            subPos += step
                            rem -= step
                        }
                    }
                    offset += encBytes
                }
            } catch (e: Exception) {
                Log.e(TAG, "decryptCbcsPayload error: ${e.message}", e)
            }
        }
    }
}
