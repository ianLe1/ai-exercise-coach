package com.example.aiexercise.data

import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.store.AppDataStore
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 仓库层：唯一负责“读/写磁盘”的地方。
 * 领域层（ExerciseToolkit）只产出新的 AppData，由这里落盘，保证两者可独立测试。
 */
class AppRepository(
    private val store: AppDataStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun load(): Outcome<AppData> = withContext(dispatcher) {
        try {
            Outcome.Success(store.load() ?: AppData())
        } catch (exception: Exception) {
            Outcome.Failure(AppError.Persistence("读取数据文件失败：${exception.message ?: exception.javaClass.simpleName}"))
        }
    }

    suspend fun save(data: AppData): Outcome<Unit> = withContext(dispatcher) {
        try {
            store.save(data)
            Outcome.Success(Unit)
        } catch (exception: Exception) {
            Outcome.Failure(AppError.Persistence("写入数据文件失败：${exception.message ?: exception.javaClass.simpleName}"))
        }
    }
}
