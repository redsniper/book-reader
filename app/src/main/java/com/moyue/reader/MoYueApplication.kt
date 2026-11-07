package com.moyue.reader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * 应用入口。
 *
 * 职责：
 * 1. 在进程启动时初始化 PDFBox 的资源加载器（PDF 字体/资源需要 Context）；
 * 2. 创建全局依赖容器 [AppContainer]，供 Activity / ViewModel 使用。
 *
 * 注意：这里不做任何文件扫描或网络请求，保证冷启动轻量。
 */
class MoYueApplication : Application() {

    /** 手写依赖容器；在 [onCreate] 中创建，进程内单例。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android 需要应用 Context 才能读取打包的字体资源。
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
    }
}
