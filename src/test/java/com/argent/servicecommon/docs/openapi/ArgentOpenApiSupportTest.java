package com.argent.servicecommon.docs.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import static org.assertj.core.api.Assertions.assertThat;

class ArgentOpenApiSupportTest {
    @ArgentApiAction("docs.publish")
    static class ExampleController {
        public void annotated() { }
        @ArgentApiAction("docs.read")
        public void overridden() { }
    }
    static class ExistingController {
        @PreAuthorize("@argentAccess.action('queue.read')")
        public void oldCode() { }
    }

    @Test
    void readsAnnotationOnClassAndMethod() throws NoSuchMethodException {
        assertThat(ArgentOpenApiSupport.action(ExampleController.class.getDeclaredMethod("annotated"),
                ExampleController.class)).isEqualTo("docs.publish");
        assertThat(ArgentOpenApiSupport.action(ExampleController.class.getDeclaredMethod("overridden"),
                ExampleController.class)).isEqualTo("docs.read");
    }

    @Test
    void readsExistingSimplePreAuthorize() throws NoSuchMethodException {
        assertThat(ArgentOpenApiSupport.action(ExistingController.class.getDeclaredMethod("oldCode"),
                ExistingController.class)).isEqualTo("queue.read");
    }
}
