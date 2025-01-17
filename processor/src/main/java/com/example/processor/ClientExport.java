package com.example.processor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RestController를 Feign으로 추출할 경우 해당 어노테이션을 사용합니다.
 * <p> {@link org.springframework.web.bind.annotation.RestController} 이면서 해당 어노테이션이 적용되어 있을 경우
 * 빌드 시 추출 프로세스를 수행합니다.
 * @see com.qcp.common.feign.processor.ClientExportAnnotationProcessor
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ClientExport {
    String extractName() default "";

    String exportPackage();
}
