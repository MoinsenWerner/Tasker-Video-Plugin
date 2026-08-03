package com.example.taskervideoplugin

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.viewbinding.ViewBinding
import com.joaomgcd.taskerpluginlibrary.SimpleResultError
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginRunner

abstract class ActivityConfigTasker<TInput : Any, TOutput : Any, TActionRunner : TaskerPluginRunner<TInput, TOutput>, THelper : TaskerPluginConfigHelper<TInput, TOutput, TActionRunner>, TBinding : ViewBinding> : Activity(), TaskerPluginConfig<TInput> {
    abstract fun getNewHelper(config: TaskerPluginConfig<TInput>): THelper
    protected abstract fun inflateBinding(layoutInflater: LayoutInflater): TBinding?
    protected var binding: TBinding? = null
    protected val taskerHelper by lazy { getNewHelper(this) }
    open val isConfigurable = true
    override val context get() = applicationContext
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionHelper.requestRequiredPermissions(this)
        binding = inflateBinding(layoutInflater)
        if (!isConfigurable) {
            taskerHelper.finishForTasker()
            return
        }
        binding?.root?.let { setContentView(it) }
        taskerHelper.onCreate()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
            val result = taskerHelper.onBackPressed()
            result.success
        } else super.onKeyDown(keyCode, event)
    }
    override fun onBackPressed() {}
}
