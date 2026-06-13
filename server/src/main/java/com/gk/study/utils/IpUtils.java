package com.gk.study.utils;

import javax.servlet.http.HttpServletRequest;

/**
 * IP 工具类
 * 从 HTTP 请求中获取客户端真实 IP 地址（兼容反向代理场景）
 */
public class IpUtils {

    private IpUtils() {}

    /**
     * 获取客户端真实 IP 地址
     * 优先从 X-Forwarded-For / X-Real-IP 等代理头中获取
     */
    public static String getIpAddr(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (isBlank(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (isBlank(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (isBlank(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (isBlank(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多层代理时取第一个 IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private static boolean isBlank(String str) {
        return str == null || str.isEmpty() || "unknown".equalsIgnoreCase(str);
    }
}
