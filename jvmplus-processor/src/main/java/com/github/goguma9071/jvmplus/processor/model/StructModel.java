package com.github.goguma9071.jvmplus.processor.model;

import javax.lang.model.element.ExecutableElement;
import java.util.List;

public record StructModel(
    String packageName,
    String interfaceName,
    String implBaseName,
    List<FieldModel> fields,
    List<ExecutableElement> nativeCalls,
    boolean hasStatic,
    String defaultLib,
    long defaultAlignment
) {}
