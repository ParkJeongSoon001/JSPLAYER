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

    override fun open(dataSpec: DataSpec): Long {
        // Range 요청을 하더라도 upstream에 그대로 전달하되,
        // 반환되는 길이는 무조건 UNSET으로 속입니다.
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstream.read(buffer, offset, length)
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
