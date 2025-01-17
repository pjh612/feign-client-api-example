package com.example.processor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 해당 어노테이션을 클래스 또는 함수 레벨에 지정하면 추출 대상 메서드를 지정할 수 있습니다.
 *
 * @see ClientExport
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Export {
}
