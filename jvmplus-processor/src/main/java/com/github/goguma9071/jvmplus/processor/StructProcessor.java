package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.processor.model.*;
import com.google.auto.service.AutoService;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.Set;

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
        for (Element element : roundEnv.getElementsAnnotatedWith(Struct.Type.class)) {
            if (element.getKind() != ElementKind.INTERFACE) continue;
            
            // 1. 모놀리식 로직이 이식된 분석기 실행
            analyzer.analyze((TypeElement) element).ifPresent(model -> {
                try {
                    // 2. 모놀리식 로직이 이식된 생성기 실행
                    generator.generate(model);
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "JPC Generator Error: " + e.getMessage(), element);
                }
            });
        }
        return true;
    }
}
