package com.bins.easyredisson.util;


import java.util.Random;
import java.util.UUID;

/**
 * @author leo-bin
 * @date 2020/1/6 17:03
 * @apiNote 一些使用高频的方法工具类
 */
public class CommonUtil {

    /**
     * description 获取指定位数的随机数
     */
    public static String getRandomString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }



    /**
     * 获得一个32位的随机数
     */
    public static String UUID32() {
        String str = UUID.randomUUID().toString();
        return str.replaceAll("-", "");
    }

}
