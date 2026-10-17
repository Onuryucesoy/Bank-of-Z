package nazare.jmp.experimental.ims;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import nazare.jmp.experimental.ExperimentalFeatures;

/** Turns the experimental IMS flag on for one test and restores it afterwards. */
class ExperimentalFlag implements BeforeEachCallback, AfterEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        System.setProperty(ExperimentalFeatures.IMS_READ_ONLY_PROPERTY, "true");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        System.clearProperty(ExperimentalFeatures.IMS_READ_ONLY_PROPERTY);
    }
}
