package com.mz.jarboot;

import com.mz.jarboot.api.constant.CommonConst;
import com.mz.jarboot.common.VersionUtils;
import com.mz.jarboot.utils.VMUtils;
import javax.swing.*;
import java.io.File;

/**
 * check file is all exist and environment is jdk.
 * @author majianzheng
 */
public class AppEnvironment {
    /**
     * 初始化并检查环境
     */
    public static void initAndCheck() {
        //初始化工作目录
        String homePath = System.getenv(CommonConst.JARBOOT_HOME);
        if (null == homePath || homePath.isEmpty()) {
            homePath = System.getProperty(CommonConst.JARBOOT_HOME, null);
            if (null == homePath) {
                homePath = System.getProperty("user.home") + File.separator + CommonConst.JARBOOT_NAME;
            }
        }
        System.setProperty(CommonConst.JARBOOT_HOME, homePath);
        System.setProperty("application.version", "v" + VersionUtils.version);
        final String derbyLog = homePath + File.separator + "logs" + File.separator + "derby.log";
        System.setProperty("derby.stream.error.file", derbyLog);

        //环境检查
        if (!checkEnvironment()) {
            System.exit(-1);
        }
    }

    private static void propDialog(String msg) {
        JOptionPane.showConfirmDialog(null, msg , "错误",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE);
    }

    private static boolean checkEnvironment() {
        String dir;
        //先检查jarboot-agent.jar文件
        File jarFile;
        String binDir = System.getProperty(CommonConst.JARBOOT_HOME) + File.separator + "bin";
        try {

            jarFile = new File(binDir, CommonConst.AGENT_JAR_NAME);
            //先尝试从当前路径下获取jar的位置，若不存在则尝试从用户目录加载
            if (!jarFile.exists()) {
                propDialog("检查环境错误，在当前目录未发现jarboot-agent.jar。");
                return false;
            }
            dir = jarFile.getParent();
        } catch (Exception e) {
            //查找jarboot-agent.jar失败
            propDialog("检查环境错误，缺失jarboot-agent.jar，请确保文件的完整性。错误：" + e.getMessage());
            return false;
        }
        //检查jarboot-core.jar文件，该文件必须和jarboot-agent.jar处于同一目录下
        jarFile = new File(dir, "jarboot-core.jar");
        if (!jarFile.exists()) {
            propDialog("检查环境错误，未发现jarboot-core.jar。");
            return false;
        }
        if (!jarFile.isFile()) {
            propDialog("检查环境错误，jarboot-core.jar不是文件类型。");
            return false;
        }

        jarFile = new File(dir, "jarboot-spy.jar");
        if (!jarFile.exists()) {
            propDialog("检查环境错误，未发现jarboot-spy.jar。");
            return false;
        }
        if (!jarFile.isFile()) {
            propDialog("检查环境错误，jarboot-spy.jar不是文件类型。");
            return false;
        }

        //检查是否是jdk环境，是否存在tools.jar
        if (!VMUtils.getInstance().isInitialized()) {
            propDialog("检查环境错误，当前运行环境未安装jdk，请检查环境变量是否配置，可能是使用了jre环境。");
            return false;
        }
        return true;
    }

    private AppEnvironment() { }
}