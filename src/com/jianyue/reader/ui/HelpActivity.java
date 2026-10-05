package com.jianyue.reader.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jianyue.reader.net.NetKit;
import com.jianyue.reader.rule.Js;

/**
 * 书源格式说明 (book-source format help).
 *
 * Kept in-app so the format is available on the device, not only in the repo docs. The text
 * mirrors 书源JSON格式说明.md, condensed for a 320x480 screen.
 *
 * This screen also logs a one-line engine status. The dedicated self-test activity was
 * removed to keep the UI minimal, but the facts it reported (whether Rhino loads, which TLS
 * protocols this device offers) are still worth being able to check on a real device - and
 * the acceptance script asserts on exactly these lines.
 */
public class HelpActivity extends Activity {

    private static final String TAG = "JianYue";

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setup(this, 0xFFF7F7F7);
        setContentView(makeView());
        logEngineStatus();
    }

    /**
     * Poke the engine so its state lands in logcat.
     *
     * Js.available() performs the real Rhino initialisation (Context.enter,
     * setOptimizationLevel(-1), initStandardObjects) and reports the failure reason if it
     * cannot run. That is the answer to "do JavaScript book-source rules work on this
     * device", which otherwise stays unverified because Rhino loads lazily.
     */
    private void logEngineStatus() {
        try {
            boolean js = Js.available();
            Log.i(TAG, "engine-status: rhino=" + (js ? "ready" : "unavailable")
                    + (js ? "" : (" reason=" + Js.unavailableReason())));
        } catch (Throwable t) {
            Log.i(TAG, "engine-status: rhino=error " + t.getClass().getName());
        }
        try {
            Log.i(TAG, "engine-status: tls=" + NetKit.protocols()
                    + " tls10Only=" + NetKit.isTls10Ceiling());
        } catch (Throwable t) {
            Log.i(TAG, "engine-status: tls=error " + t.getClass().getName());
        }
    }

    private View makeView() {
        TextView tv = new TextView(this);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF444444);
        tv.setLineSpacing(6f, 1f);
        tv.setPadding(dp(14), dp(14), dp(14), dp(20));
        tv.setText(HELP.replace("@VERSION@", appVersion()));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF7F7F7);
        sv.addView(tv);
        return sv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * The version shown on screen is read back from the installed APK's manifest, never
     * hard-coded here: version.txt is the one place it is written down, build-reader.ps1
     * injects it into the manifest, and PackageManager hands it back at runtime.
     */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static final String HELP =
            "书源 JSON 结构\n"
            + "────────────────────\n"
            + "{\n"
            + "  \"bookSourceName\": \"站点名\",\n"
            + "  \"bookSourceUrl\": \"http://站点域名\",\n"
            + "  \"searchUrl\": \"http://...?kw={{key}}&p={{page}}\",\n"
            + "  \"ruleSearch\": {\n"
            + "    \"bookList\": \"$.data.list\",\n"
            + "    \"name\": \"name##<[^>]+>##\",\n"
            + "    \"author\": \"authorName\",\n"
            + "    \"bookUrl\": \"/book/{$.bookId}\"\n"
            + "  },\n"
            + "  \"ruleBookInfo\": { \"name\": \"class.bookname@text\" },\n"
            + "  \"ruleToc\": {\n"
            + "    \"tocUrl\": \"http://别的域名/list/{$.bookId}.html\",\n"
            + "    \"chapterList\": \"class.chapter-list@tag.a\",\n"
            + "    \"chapterName\": \"text\",\n"
            + "    \"chapterUrl\": \"href\"\n"
            + "  },\n"
            + "  \"ruleContent\": { \"content\": \"id.Jcontent@html\" }\n"
            + "}\n\n"
            + "变量\n"
            + "────────────────────\n"
            + "{{key}}   搜索词（自动编码）\n"
            + "{{page}}  页码\n"
            + "{$.字段}  引用 JSON 当前项，如 /book/{$.bookId}\n\n"
            + "规则四种写法\n"
            + "────────────────────\n"
            + "$.data.list      JSONPath\n"
            + "class.xxx@text   CSS 选择器（class. / id. / tag.）\n"
            + "//div[@id='x']   XPath\n"
            + "@js:表达式        JavaScript\n"
            + "规则后可接 ##正则##替换\n\n"
            + "取值后缀\n"
            + "────────────────────\n"
            + "@text     文本\n"
            + "@html     内部 HTML\n"
            + "@href/@src 取属性\n"
            + "class.x@tag.a  取容器里的所有 <a>\n\n"
            + "写书源的经验（实测）\n"
            + "────────────────────\n"
            + "1. 先确认站点在 API 1 上可达。\n"
            + "   本机 TLS 上限是 TLS 1.0，只支持\n"
            + "   TLS 1.2+ 的站点用不了，比如起点。\n"
            + "   能用 http:// 就别用 https://。\n\n"
            + "2. 优先找 JSON 接口。很多站点是\n"
            + "   前后端分离的，HTML 只是空壳。\n\n"
            + "3. tocUrl 若目录在别的子域，必须写\n"
            + "   绝对地址，否则会按详情页域名拼接。\n\n"
            + "4. 书名常带高亮标签，用\n"
            + "   ##<[^>]+>## 清洗。\n\n"
            + "5. 书源很易碎：站点改版就会失效。\n"
            + "   尽量用语义化 class 名而不是位置。\n"
            + "\n"
            + "关于本应用\n"
            + "────────────────────\n"
            + "简阅 · Android 1.0 (API Level 1) 专用\n"
            + "纯 Java · 无 AndroidX · 无 Kotlin\n"
            + "包名 com.jianyue.reader\n"
            + "版本 @VERSION@\n"
            + "\n"
            + "已知平台限制（实测）\n"
            + "────────────────────\n"
            + "· TLS 上限 TLS 1.0：只支持 TLS 1.2+\n"
            + "  的站点无法访问（例如起点）\n"
            + "· 商业站 VIP 章节需订阅，\n"
            + "  只能读到免费部分\n"
            + "· 未实现 E4X（JS 里的 XML 字面量）\n"
            + "· 无 AndroidX / Material，自绘界面\n";
}
