package com.privatecctv.camera

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

class StorageManager(
    private val context: Context,
    private val maxSizeBytes: Long = 100L * 1024 * 1024 * 1024  // 100GB
) {
    companion object {
        private const val TAG = "StorageManager"
        private const val FOLDER_NAME = "BomCCTV"
    }

    val recordingDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    /**
     * 전체 녹화 용량 계산 (바이트)
     */
    fun getTotalSize(): Long {
        return calculateDirSize(recordingDir)
    }

    /**
     * 전체 녹화 용량 (읽기 쉬운 형식)
     */
    fun getTotalSizeFormatted(): String {
        val bytes = getTotalSize()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * 용량 초과 시 오래된 파일부터 삭제
     */
    fun cleanupIfNeeded(): Int {
        var deletedCount = 0
        var currentSize = getTotalSize()

        if (currentSize <= maxSizeBytes) {
            Log.d(TAG, "용량 여유 있음: ${getTotalSizeFormatted()}")
            return 0
        }

        Log.d(TAG, "용량 초과! ${getTotalSizeFormatted()} > ${maxSizeBytes / (1024 * 1024 * 1024)}GB, 정리 시작...")

        // 모든 파일을 수정 시간 기준 정렬
        val allFiles = getAllRecordingFiles().sortedBy { it.lastModified() }

        for (file in allFiles) {
            if (currentSize <= maxSizeBytes * 0.9) {  // 90%까지 정리
                break
            }

            val fileSize = file.length()
            if (file.delete()) {
                currentSize -= fileSize
                deletedCount++
                Log.d(TAG, "삭제됨: ${file.name} (${fileSize / 1024}KB)")

                // 빈 폴더 삭제
                file.parentFile?.let { parent ->
                    if (parent.listFiles()?.isEmpty() == true) {
                        parent.delete()
                        Log.d(TAG, "빈 폴더 삭제: ${parent.name}")
                    }
                }
            }
        }

        Log.d(TAG, "정리 완료: ${deletedCount}개 파일 삭제, 남은 용량: ${getTotalSizeFormatted()}")
        return deletedCount
    }

    /**
     * 모든 녹화 파일 목록
     */
    fun getAllRecordingFiles(): List<File> {
        val files = mutableListOf<File>()
        recordingDir.listFiles()?.forEach { dateFolder ->
            if (dateFolder.isDirectory) {
                dateFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.let {
                    files.addAll(it)
                }
            }
        }
        return files
    }

    /**
     * 날짜별 폴더 목록
     */
    fun getDateFolders(): List<File> {
        return recordingDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    }

    /**
     * 특정 날짜의 녹화 파일 목록
     */
    fun getRecordingsForDate(dateFolder: File): List<File> {
        return dateFolder.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.sortedByDescending { it.name } ?: emptyList()
    }

    /**
     * 녹화 파일 개수
     */
    fun getRecordingCount(): Int {
        return getAllRecordingFiles().size
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirSize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    /**
     * 모든 녹화 삭제
     */
    fun deleteAll(): Int {
        var deletedCount = 0
        recordingDir.listFiles()?.forEach { dateFolder ->
            if (dateFolder.isDirectory) {
                dateFolder.listFiles()?.forEach { file ->
                    if (file.delete()) deletedCount++
                }
                dateFolder.delete()
            }
        }
        Log.d(TAG, "전체 삭제: ${deletedCount}개 파일")
        return deletedCount
    }
}
