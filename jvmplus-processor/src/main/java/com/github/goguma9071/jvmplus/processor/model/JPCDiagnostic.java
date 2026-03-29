package com.github.goguma9071.jvmplus.processor.model;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public class JPCDiagnostic {
    private final Messager messager;

    public JPCDiagnostic(Messager messager) {
        this.messager = messager;
    }

    /**
     * Rust 스타일의 고해상도 진단 메시지를 출력합니다.
     */
    public void error(Element element, String code, String message, 
                      long currentOffset, long requiredAlign, 
                      String help, String suggestionCode) {
        
        String fileName = element.getEnclosingElement().getSimpleName().toString() + ".java";
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n")
          .append("error[").append(code).append("]: ").append(message).append("\n")
          .append("  --> ").append(fileName).append("\n")
          .append("   |\n")
          .append("   | ").append(element.toString()).append("\n")
          .append("   | ").append(" ".repeat(Math.max(0, element.toString().length() - 4))).append("^^^^ misaligned here\n")
          .append("   |\n")
          .append("   = note: field current offset: ").append(currentOffset).append(" bytes\n")
          .append("   = note: required alignment  : ").append(requiredAlign).append(" bytes\n")
          .append("   = help: ").append(help).append("\n\n")
          .append("    +----------------------------------------------------------------------+\n")
          .append("    | SUGGESTED FIX                                                        |\n")
          .append("    +----------------------------------------------------------------------+\n")
          .append("    ").append(suggestionCode.replace("\n", "\n    ")).append("\n")
          .append("    +----------------------------------------------------------------------+\n");

        messager.printMessage(Diagnostic.Kind.ERROR, sb.toString(), element);
    }
}
