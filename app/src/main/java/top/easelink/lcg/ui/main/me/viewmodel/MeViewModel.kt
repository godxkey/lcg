package top.easelink.lcg.ui.main.me.viewmodel

import android.annotation.SuppressLint
import android.text.TextUtils
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import timber.log.Timber
import top.easelink.lcg.LCGApp
import top.easelink.lcg.R
import top.easelink.lcg.mta.*
import top.easelink.lcg.service.web.WebViewWrapper
import top.easelink.lcg.service.work.SignInWorker
import top.easelink.lcg.service.work.SignInWorker.Companion.DEFAULT_TIME_UNIT
import top.easelink.lcg.service.work.SignInWorker.Companion.WORK_INTERVAL
import top.easelink.lcg.ui.info.UserData
import top.easelink.lcg.ui.main.me.model.NotificationInfo
import top.easelink.lcg.ui.main.me.model.UserInfo
import top.easelink.lcg.ui.main.source.local.SPConstants.*
import top.easelink.lcg.ui.main.source.local.SharedPreferencesHelper
import top.easelink.lcg.utils.WebsiteConstant.HOME_URL
import top.easelink.lcg.utils.WebsiteConstant.SERVER_BASE_URL
import top.easelink.lcg.utils.getCookies
import java.util.*

class MeViewModel: ViewModel() {

    private val mLoginState = MutableLiveData<Boolean>()
    private val mUserInfo = MutableLiveData<UserInfo>()
    private val mAutoSignInEnable = MutableLiveData<Boolean>()
    private val mSyncFavoriteEnable = MutableLiveData<Boolean>()
    private val mNotificationInfo = MutableLiveData<NotificationInfo>()

    init {
        mAutoSignInEnable.postValue(SharedPreferencesHelper
            .getUserSp()
            .getBoolean(SP_KEY_AUTO_SIGN_IN, false))
        mSyncFavoriteEnable.postValue(SharedPreferencesHelper
            .getUserSp()
            .getBoolean(SP_KEY_SYNC_FAVORITE, false))
    }

    val workInfo:LiveData<List<WorkInfo>>
        get() = WorkManager.getInstance().getWorkInfosByTagLiveData(SignInWorker::class.java.simpleName)

    val loginState: LiveData<Boolean>
        get() = mLoginState

    val userInfo:LiveData<UserInfo>
        get() = mUserInfo

    val autoSignEnable: LiveData<Boolean>
        get() = mAutoSignInEnable

    val syncFavorite: LiveData<Boolean>
        get() = mSyncFavoriteEnable

    val notificationInfo: LiveData<NotificationInfo>
        get() = mNotificationInfo

    fun scheduleJob(v: View) {
        val nextState = (mAutoSignInEnable.value != true)
        mAutoSignInEnable.postValue(nextState)
        SharedPreferencesHelper.getUserSp()
            .edit()
            .putBoolean(SP_KEY_AUTO_SIGN_IN, nextState)
            .apply()
        if (nextState) {
            val constraints = Constraints.Builder()
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()
            val request = PeriodicWorkRequest.Builder(SignInWorker::class.java, WORK_INTERVAL, DEFAULT_TIME_UNIT)
                .setConstraints(constraints)
                .addTag(SignInWorker::class.java.simpleName)
                .build()
            WorkManager.getInstance().enqueue(request)
        } else {
            WorkManager.getInstance().cancelAllWorkByTag(SignInWorker::class.java.simpleName)
        }
        sendKVEvent(EVENT_AUTO_SIGN, Properties().apply {
            setProperty(PROP_IS_AUTO_SIGN_ENABLE, nextState.toString())
        })
    }

    fun fetchUserInfoDirect() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val doc = Jsoup
                    .connect("$SERVER_BASE_URL$HOME_URL?mod=spacecp&ac=credit&showcredit=1")
                    .cookies(getCookies())
                    .ignoreHttpErrors(true)
                    .get()
                val userInfo = parseUserInfo(doc)
                if (userInfo.userName.isNullOrEmpty()) {
                    disableAutoSign()
                    clearCookies()
                    UserData.loggedInState = false
                    mLoginState.postValue(false)
                } else {
                    UserData.loggedInState = true
                    mLoginState.postValue(true)
                    mUserInfo.postValue(userInfo)
                    SharedPreferencesHelper.getUserSp().edit().putBoolean(SP_KEY_LOGGED_IN, true)
                        .apply()
                }
                mNotificationInfo.postValue(parseNotificationInfo(doc))
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun clearLocalCookies() {
        UserData.loggedInState = false
        clearCookies()
        WebViewWrapper.getInstance().clearCookies()
        showClearMessage()
    }

    private fun showClearMessage() {
        with(LCGApp.getContext()) {
            Toast.makeText(
                this,
                getString(R.string.me_tab_clear_cookie),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun clearCookies() {
        SharedPreferencesHelper.getCookieSp().edit().clear().apply()
    }

    private fun parseNotificationInfo(doc: Document): NotificationInfo {
        with(doc) {
            val menu = getElementById("myprompt_menu")
            val requestList = mutableListOf<String>()
            var message = 0
            var follower = 0
            var myPost = 0
            var systemNotifs = 0
            menu?.select("li")?.forEach {
                try {
                    it.select("a > span").first()?.text()?.also { v ->
                        Timber.d(it.toString())
                        if (v.isNotBlank() && v.toInt() >= 1) {
                            it.selectFirst("a")?.attr("href")?.also { url ->
                                requestList.add(url)
                                when {
                                    url.contains("mypost") -> myPost++
                                    url.contains("follower") -> follower++
                                    url.contains("pm") -> message++
                                    url.contains("system") -> systemNotifs++
                                    else -> {
                                        // do nothing
                                    }
                                }
                            }
                        }
                    }
                } catch (e :NumberFormatException) {
                    // don't care
                }
            }
            return NotificationInfo(message, follower, myPost, systemNotifs)
        }
    }

    private fun parseUserInfo(doc: Document): UserInfo {
        with(doc) {
            val userName = getElementsByClass("vwmy")?.first()?.firstElementSibling()?.text()
            return if (!TextUtils.isEmpty(userName)) {
                val avatar = selectFirst("div.avt > a > img")?.attr("src")
                val groupInfo = getElementById("g_upmine")?.text()
                getElementsByClass("xi2")?.remove()
                val coin = getElementsByClass("xi1 cl")?.first()?.text()
                val element = selectFirst("span.xg1")
                val parentCredit = element?.parent()
                element?.remove()
                val credit = parentCredit?.text()
                val signInState = selectFirst("img.qq_bind")?.attr("src")
                UserInfo(userName, avatar, groupInfo, coin, credit, signInState)
            } else {
                UserInfo(getElementById("messagetext")?.text())
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    @WorkerThread
    private fun disableAutoSign() {
        WorkManager.getInstance().cancelAllWorkByTag(SignInWorker::class.java.simpleName)
        SharedPreferencesHelper.getUserSp().edit().putBoolean(SP_KEY_AUTO_SIGN_IN, false).commit()
        mAutoSignInEnable.postValue(false)
    }

    fun setSyncFavorite(v: View) {
        val nextState = (mSyncFavoriteEnable.value != true)
        mSyncFavoriteEnable.postValue(nextState)
        sendKVEvent(EVENT_SYNC_FAVORITE, Properties().apply {
            setProperty(
                PROP_IS_SYNC_FAVORITE_ENABLE,
                nextState.toString()
            )
        })
        SharedPreferencesHelper.getUserSp()
            .edit()
            .putBoolean(SP_KEY_SYNC_FAVORITE, nextState)
            .apply()
    }
}
