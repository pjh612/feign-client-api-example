package com.example.processor;

import com.google.auto.service.AutoService;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.javapoet.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feign Client를 자동으로 생성해주는 Processor 입니다.
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class ClientExportAnnotationProcessor extends AbstractProcessor {

    private static final String APPLICATION_NAME_PROPERTY = "applicationName";
    private static final String EXPORT_MODULE_ABSOLUTE_PATH_PROPERTY = "exportModulePath";
    private static final String EXPORT_BASE_DIRECTORY = "/src/main/java";

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ClientExport.class.getName());
    }

    private static final Set<String> supportedRequestMappingAnnotationTypes = Set.of(
            GetMapping.class.getName(),
            PostMapping.class.getName(),
            PutMapping.class.getName(),
            DeleteMapping.class.getName(),
            PatchMapping.class.getName(),
            RequestMapping.class.getName()
    );

    private static final Set<String> supportedParameterAnnotationTypes = Set.of(
            RequestHeader.class.getName(),
            RequestParam.class.getName(),
            RequestBody.class.getName(),
            PathVariable.class.getName(),
            ModelAttribute.class.getName()
    );

    /**
     * build.gradle로 부터 추가된 {@link #APPLICATION_NAME_PROPERTY}, {@link #EXPORT_MODULE_ABSOLUTE_PATH_PROPERTY} 프로퍼티를 읽어
     * {#link @ClientExport} 어노테이션이 달려있는 RestController를 찾아 FeignClient 추출을 수행합니다.
     *
     * @param annotations the annotation interfaces requested to be processed
     * @param roundEnv  environment for information about the current and prior round
     * @return boolean
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, String> options = processingEnv.getOptions();
        String applicationName = options.get(APPLICATION_NAME_PROPERTY);
        String exportModulePath = options.get(EXPORT_MODULE_ABSOLUTE_PATH_PROPERTY);

        assert applicationName != null : "please set " + APPLICATION_NAME_PROPERTY + "property";
        assert  exportModulePath != null : "please set " + EXPORT_MODULE_ABSOLUTE_PATH_PROPERTY + "property";

        Set<? extends Element> classElements = roundEnv.getElementsAnnotatedWithAny(Set.of(RestController.class, Controller.class));
        for (Element classElement : classElements) {
            if (!haveToGenerate(classElement)) {
                continue;
            }

            ClientExport clientExportAnnotation = classElement.getAnnotation(ClientExport.class);
            Export exportAnnotation = classElement.getAnnotation(Export.class);
            RequestMapping requestMappingAnnotation = classElement.getAnnotation(RequestMapping.class);

            String extractName = getExtractName(clientExportAnnotation, classElement);
            String baseExtractName = getExtractName(clientExportAnnotation, classElement) + "Base";
            String exportPackage = clientExportAnnotation.exportPackage();
            String baseExportPackage = clientExportAnnotation.exportPackage() + ".base";
            String basePath = getBasePath(requestMappingAnnotation);

            Map<Integer, MethodSpec> methodSpecMap = getMethodSpecMap(Map.of(), classElement, exportAnnotation != null);
            TypeSpec.Builder baseInterfaceBuilder = TypeSpec.interfaceBuilder(baseExtractName)
                    .addModifiers(Modifier.PUBLIC)
                    .addMethods(methodSpecMap.values());
            exportInterface(exportModulePath, baseExportPackage, baseInterfaceBuilder.build());

            Path clientPath = Path.of(exportModulePath, EXPORT_BASE_DIRECTORY, exportPackage.replaceAll("\\.", "/"), extractName + ".java");
            if (Files.notExists(clientPath)) {
                TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(extractName)
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(buildInterfaceHeadAnnotation(applicationName, basePath))
                        .addSuperinterface(ClassName.get(baseExportPackage, baseExtractName));
                exportInterface(exportModulePath, exportPackage, interfaceBuilder.build());
            }
        }
        return true;
    }

    /**
     * 추출될 클래스 명(파일 명)을 지정합니다.
     *
     * <br> {@link ClientExport}의 {@link ClientExport#extractName()}이 있다면 해당 값을 사용하고
     * 없다면 추출 대상의 클래스 명을 활용합니다.
     *
     * <br> {@link ClientExport#extractName()}이 없다면
     * Controller 클래스 명에 포함된 "Controller" 는 제거 시킨 후 "Client"를 맨 뒤에 붙입니다.
     *
     * @param clientExportAnnotation
     * @param classElement
     * @return 추출될 클래스의 이름
     */
    private String getExtractName(ClientExport clientExportAnnotation, Element classElement) {
        String extractName = clientExportAnnotation.extractName();
        return extractName == null || extractName.isBlank()
                ? classElement.getSimpleName().toString().replace("Controller", "") + "Client"
                : clientExportAnnotation.extractName();
    }

    private Map<Integer, MethodSpec> getMethodSpecMap(Map<Integer, MethodSpec> methodSpecs, Element classElement, boolean targetAllMethod) {
        Map<Integer, MethodSpec> result = new HashMap<>();

        TypeMirror currentTypeMirror = classElement.asType();
        TypeName currentTypeName = ClassName.get(currentTypeMirror);
        if (currentTypeName.toString().equals(Object.class.getName())) {
            return result;
        }

        classElement.getEnclosedElements()
                .stream()
                .filter(elementToFilter -> {
                    boolean isSupportedAnnotation = elementToFilter.getAnnotationMirrors()
                            .stream()
                            .anyMatch(it -> supportedRequestMappingAnnotationTypes.contains(it.getAnnotationType().toString()));
                    boolean isTargetMethod = targetAllMethod || elementToFilter.getAnnotation(Export.class) != null;

                    return elementToFilter.getKind() == ElementKind.METHOD && isTargetMethod && isSupportedAnnotation;
                })
                .map(ExecutableElement.class::cast)
                .forEach(methodElement -> {
                    int key = methodElement.toString().hashCode();
                    if (!methodSpecs.containsKey(key)) {
                        result.put(key, buildMethodSpec(methodElement));
                    }
                });

        Element superElement = getSuperElement(classElement);
        Map<Integer, MethodSpec> methodSpecMap = getMethodSpecMap(result, superElement, targetAllMethod);
        result.putAll(methodSpecMap);

        return result;
    }

    private boolean haveToGenerate(Element classElement) {
        return classElement instanceof TypeElement && classElement.getKind() == ElementKind.CLASS && classElement.getAnnotation(ClientExport.class) != null
                && (classElement.getAnnotation(RestController.class) != null || classElement.getAnnotation(Controller.class) != null && classElement.getAnnotation(ResponseBody.class) != null);
    }

    private String getBasePath(RequestMapping requestMappingAnnotation) {
        if (requestMappingAnnotation != null && requestMappingAnnotation.value() != null && requestMappingAnnotation.value().length != 0) {
            return requestMappingAnnotation.value()[0];
        }

        return "";
    }

    /**
     * 인터페이스 상단 정의 부분에 적용될 어노테이션 스펙을 빌드합니다.
     *
     * @param applicationName
     * @param basePath
     * @return AnnotationSpec
     */
    private AnnotationSpec buildInterfaceHeadAnnotation(String applicationName, String basePath) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(FeignClient.class)
                .addMember("name", "$S", applicationName);
        if (basePath != null && !basePath.isBlank()) {
            builder.addMember("path", "$S", basePath);
        }

        return builder.build();
    }

    /**
     * 클래스가 상속관계일 경우 부모 Element를 반환합니다.
     *
     * @param classElement
     * @return 부모 Element
     */
    private Element getSuperElement(Element classElement) {
        assert classElement.getKind() == ElementKind.CLASS || classElement.getKind() == ElementKind.INTERFACE : "Not Class";

        TypeMirror superTypeMirror = classElement.asType();
        TypeElement superTypeElement = (TypeElement) processingEnv.getTypeUtils().asElement(superTypeMirror);
        DeclaredType superClassType = (DeclaredType) superTypeElement.getSuperclass();

        return superClassType.asElement();
    }

    /**
     * {@link ExecutableElement}를 {@link MethodSpec}으로 만듭니다.
     *
     * @param methodElement Class Element에 포함된 ExecutableElement
     * @return MethodSpec
     */
    private MethodSpec buildMethodSpec(ExecutableElement methodElement) {
        return MethodSpec.methodBuilder(methodElement.getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotations(buildMethodAnnotationSpecs(methodElement))
                .addParameters(buildParameterSpecs(methodElement.getParameters()))
                .returns(TypeName.get(methodElement.getReturnType()))
                .build();
    }

    /**
     * 메서드에 적용되어있는 어노테이션 정보를 가져와 {@link AnnotationSpec}으로 만들어줍니다.
     *
     * @param methodElement Class Element에 포함된 ExecutableElement
     * @return AnnotationSpec 리스트
     */
    private List<AnnotationSpec> buildMethodAnnotationSpecs(ExecutableElement methodElement) {
        return methodElement.getAnnotationMirrors()
                .stream()
                .filter(it -> supportedRequestMappingAnnotationTypes.contains(it.getAnnotationType().toString()))
                .map(annotationMirror -> {
                    TypeElement element = (TypeElement) annotationMirror.getAnnotationType().asElement();
                    AnnotationSpec.Builder builder = AnnotationSpec.builder(ClassName.get(element));
                    annotationMirror.getElementValues()
                            .forEach((key, value) -> builder.addMember(key.getSimpleName().toString(), "$L", value));

                    return builder.build();
                })
                .toList();
    }


    /**
     * {@link VariableElement}의 List를 받아 {@link ParameterSpec}으로 변환합니다.
     *
     * @param parameters 메서드의 파라미터 정보를 포함한 Element의 List
     * @return ParameterSpec 리스트
     */
    private List<ParameterSpec> buildParameterSpecs(List<? extends VariableElement> parameters) {
        return parameters.stream()
                .map(parameter -> ParameterSpec.builder(TypeName.get(parameter.asType()), parameter.getSimpleName().toString())
                        .addAnnotations(buildParameterAnnotationSpecs(parameter.getSimpleName().toString(), parameter.getAnnotationMirrors()))
                        .build())
                .toList();
    }

    /**
     * 매개변수에 적용된 어노테이션 정보를 읽어 {@link AnnotationSpec}으로 변환합니다.
     * <p>
     *     Feign Client에서는 {@code @RequestParam}이나 {@code @PathVaraible}일 경우
     *     Spring Web과 다르게 value 값을 필요로 하므로 value값이 없을 경우 매개변수 명을 삽입해줍니다.
     * </p>
     *
     * @param parameterName value 값에 들어갈 매개변수 명
     * @param annotationMirrors 어노테이션 정보
     * @return 어노테이션 스펙 리스트
     */
    private List<AnnotationSpec> buildParameterAnnotationSpecs(String parameterName, List<? extends AnnotationMirror> annotationMirrors) {
        return annotationMirrors.stream()
                .filter(it -> supportedParameterAnnotationTypes.contains(it.getAnnotationType().toString()))
                .map(annotationMirror -> {
                    TypeElement element = (TypeElement) annotationMirror.getAnnotationType().asElement();
                    ClassName className = ClassName.get(element);
                    AnnotationSpec.Builder builder = AnnotationSpec.builder(className);
                    annotationMirror.getElementValues()
                            .forEach((key, value) -> builder.addMember(key.getSimpleName().toString(), "$L", value));
                    boolean needParameterValue = className.simpleName().equals(RequestParam.class.getSimpleName()) && !builder.members.containsKey("value")
                            || className.simpleName().equals(PathVariable.class.getSimpleName()) && !builder.members.containsKey("value");
                    if (needParameterValue) {
                        builder.addMember("value", "$S", parameterName);
                    }

                    return builder.build();
                })
                .toList();
    }

    /**
     *
     * @param exportModulePath 추출될 모듈의 절대 경로
     * @param exportPackage 추출될 패키지 위치
     * @param typeSpec 완성된 인터페이스 스펙
     */
    private void exportInterface(String exportModulePath, String exportPackage, TypeSpec typeSpec) {
        try {
            JavaFile.builder(exportPackage, typeSpec)
                    .build()
                    .writeTo(Path.of(exportModulePath, EXPORT_BASE_DIRECTORY));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
