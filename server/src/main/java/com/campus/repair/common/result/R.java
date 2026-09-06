package com.campus.repair.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private long timestamp;

    public static <T> R<T> success() {
        return success(null);
    }

    public static <T> R<T> success(T data) {
        return new R<>(200, "操作成功", data, System.currentTimeMillis());
    }

    public static <T> R<T> success(String message, T data) {
        return new R<>(200, message, data, System.currentTimeMillis());
    }

    public static <T> R<T> error(int code, String message) {
        return new R<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> R<T> error(String message) {
        return error(500, message);
    }
}
