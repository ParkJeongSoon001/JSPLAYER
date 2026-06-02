package com.sv21c.jsplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 프록시 서버(예: Solid Explorer)가 Range 요청을 무시하고 항상 HTTP 200(전체 파일)을
 * 반환할 때, ExoPlayer가 파일의 끝(MKV Cues/Index 등)으로 탐색을 시도하는 것을
 * 원천 차단하기 위한 DataSource 래퍼입니다.
 * 
 * open()에서 항상 C.LENGTH_UNSET을 반환하여, 스트림을 "길이를 알 수 없는 라이브 스트림"으로
 * 인식하게 만들어 순차 재생만 수행하도록 강제합니다.
 */
class UnseekableDataSource(private val upstream: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    private var bytesReadTotal: Long = 0L
    private var isAviFormat: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        bytesReadTotal = 0L
        isAviFormat = false
        // Range 요청을 하더라도 upstream에 그대로 전달하되,
        // 반환되는 길이는 무조건 UNSET으로 속입니다.
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0) {
            // 첫 번째 읽기에서 실제 파일 포맷이 AVI(RIFF 헤더)인지 확인합니다.
            // (확장자만 .avi이고 실제론 MKV 등인 경우를 필터링하기 위함)
            if (bytesReadTotal == 0L && bytesRead >= 4) {
                if (buffer[offset] == 'R'.code.toByte() &&
                    buffer[offset + 1] == 'I'.code.toByte() &&
                    buffer[offset + 2] == 'F'.code.toByte() &&
                    buffer[offset + 3] == 'F'.code.toByte()
                ) {
                    isAviFormat = true
                }
            }

            // 진짜 AVI 파일의 초반부(헤더 영역)일 때만 'avih' 청크 탐색 및 변조 수행
            if (isAviFormat && bytesReadTotal < 8192L) {
                // avih(4) + 크기(4) + 패딩(4) + flags(4+1) = 최소 21바이트 필요
                // flagOffset = i + 20 이므로 i + 20 < offset + bytesRead 보장
                val searchEnd = offset + bytesRead - 21
                if (searchEnd > offset) {
                    for (i in offset until searchEnd) {
                        if (buffer[i] == 'a'.code.toByte() &&
                            buffer[i + 1] == 'v'.code.toByte() &&
                            buffer[i + 2] == 'i'.code.toByte() &&
                            buffer[i + 3] == 'h'.code.toByte()
                        ) {
                            // avih 청크 발견! dwFlags는 'avih' 문자열로부터 20바이트 뒤에 위치 (Little Endian)
                            // AVIF_HASINDEX = 0x00000010
                            val flagOffset = i + 20
                            if (flagOffset < offset + bytesRead) {
                                val oldByte = buffer[flagOffset].toInt()
                                if ((oldByte and 0x10) == 0x10) {
                                    buffer[flagOffset] = (oldByte and 0xEF).toByte()
                                    android.util.Log.d("UnseekableDataSource", "Spoofed AVIF_HASINDEX flag at offset ${bytesReadTotal + i - offset}")
                                }
                            } else {
                                android.util.Log.w("UnseekableDataSource", "avih found but flagOffset out of buffer bounds")
                            }
                            break // avih 청크는 하나만 존재함
                        }
                    }
                }
            }
            bytesReadTotal += bytesRead
        }
        return bytesRead
    }

    override fun getUri(): Uri? {
        return upstream.uri
    }

    override fun close() {
        upstream.close()
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return upstream.responseHeaders
    }
    
    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return UnseekableDataSource(upstreamFactory.createDataSource())
        }
    }
}
