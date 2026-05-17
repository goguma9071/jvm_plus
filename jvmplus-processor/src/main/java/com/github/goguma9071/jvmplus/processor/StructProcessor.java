package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.processor.model.*;
import com.google.auto.service.AutoService;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes("com.github.goguma9071.jvmplus.memory.Struct.Type")
@SupportedSourceVersion(SourceVersion.RELEASE_22)
public class StructProcessor extends AbstractProcessor {

    private JPCAnalyzer analyzer;
    private JPCDiagnostic diag;
    private JPCGenerator generator;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.diag = new JPCDiagnostic(processingEnv.getMessager());
        this.analyzer = new JPCAnalyzer(processingEnv.getElementUtils(), processingEnv.getTypeUtils(), diag);
        this.generator = new JPCGenerator(processingEnv.getFiler(), processingEnv.getTypeUtils());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, List<StructModel>> packageModels = new HashMap<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(Struct.Type.class)) {
            if (element.getKind() != ElementKind.INTERFACE) continue;
            
            analyzer.analyze((TypeElement) element).ifPresent(model -> {
                packageModels.computeIfAbsent(model.packageName(), k -> new ArrayList<>()).add(model);
            });
        }

        // 라운드에서 수집된 모든 모델을 패키지별 JPINTERNAL 클래스로 생성
        for (Map.Entry<String, List<StructModel>> entry : packageModels.entrySet()) {
            try {
                generator.generateMegaClass(entry.getKey(), entry.getValue());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "JPC Mega-Generator Error: " + e.getMessage());
            }
        }

        return true;
    }
}
