package net.coderbot.iris.pipeline.transform.parameter;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import com.gtnewhorizons.angelica.glsm.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.pipeline.transform.Patch;
import net.coderbot.iris.shaderpack.texture.TextureStage;

public class DHParameters extends Parameters {
    public final boolean secondaryView;
    public DHParameters(Patch patch, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        this(patch, textureMap, false);
    }

    public DHParameters(Patch patch, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap, boolean secondaryView) {
        super(patch, textureMap);
        this.secondaryView = secondaryView;
    }

    @Override public int hashCode() { return 31 * super.hashCode() + Boolean.hashCode(secondaryView); }
    @Override public boolean equals(Object other) {
        return super.equals(other) && secondaryView == ((DHParameters) other).secondaryView;
    }

    @Override
    public TextureStage getTextureStage() {
        return TextureStage.GBUFFERS_AND_SHADOW;
    }
}
