package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopeDHTerrainTransformTest {
    private static final String VERTEX = "#version 330 core\nvoid main(){gl_Position=ftransform();}";
    private static final String FRAGMENT = "#version 330 core\nvoid main(){gl_FragData[0]=vec4(1.0);}";

    @BeforeAll static void init() throws Exception {
        Reflect.setStatic(RenderSystem.class, "maxGlslVersion", 460); ShaderTransformer.init();
    }

    @Test void nativeOwnershipIsPrivateAndCannotContaminateCachedPrimaryPrograms() {
        for (boolean first : new boolean[]{false, true}) {
            TransformPatcher.clearCache(); ShaderTransformer.clearCache();
            for (boolean scope : new boolean[]{first, !first, first}) {
                String fragment = TransformPatcher.patchDHTerrain("scope_test", VERTEX, null, null, null, FRAGMENT,
                    Object2ObjectMaps.emptyMap(), scope).get(PatchShaderType.FRAGMENT);
                assertEquals(scope, fragment.contains("iris_scopeNativeDepth"));
                if (scope) {
                    assertTrue(fragment.contains("discard"));
                    assertTrue(fragment.indexOf("discard") < fragment.lastIndexOf("vec4"));
                }
            }
        }
    }
}
