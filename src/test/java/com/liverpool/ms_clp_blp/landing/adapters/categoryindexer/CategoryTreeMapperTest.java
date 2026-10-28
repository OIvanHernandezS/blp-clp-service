package com.liverpool.ms_clp_blp.landing.adapters.categoryindexer;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.categoryindexer.dto.CategoryTreeResponse;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule-for-rule with digital_bff's {@code mapCategoryTransform}. */
class CategoryTreeMapperTest {

    private final CategoryTreeMapper mapper = new CategoryTreeMapper();

    @Test
    void root_isUpperCasedWithAncestryOutermostFirst_andCarriesTheBreadcrumb() {
        CategoryData category = mapper.toCategory(node("catst1", "Cuidado", true, true, null,
                "ggg", "gg", "g", "p", "home > Cuidado", List.of()));

        assertThat(category.id()).isEqualTo("CATST1");
        assertThat(category.name()).isEqualTo("Cuidado");
        assertThat(category.displayName()).isEqualTo("Cuidado");
        assertThat(category.hidden()).isFalse();
        assertThat(category.ancestry()).containsExactly("ggg", "gg", "g", "p");
        assertThat(category.breadcrumb()).isEqualTo("home > Cuidado");
        assertThat(category.metadata().categoryId()).isEqualTo("CATST1");
    }

    @Test
    void absentMetadata_takesTheLegacyDefaults() {
        CategoryData category = mapper.toCategory(node("c1", "T", true, true, null,
                null, null, null, null, null, null));

        assertThat(category.breadcrumb()).isEmpty();
        assertThat(category.ancestry()).isEmpty();
        assertThat(category.children()).isEmpty();
        assertThat(category.metadata().groupType()).isEqualTo("Not Specified");
        assertThat(category.metadata().imageUrl()).isEmpty();
        assertThat(category.metadata().categoryImage()).isEmpty();
        assertThat(category.metadata().rootIndex()).isZero();
        assertThat(category.metadata().isBrand()).isFalse();
        assertThat(category.metadata().rootDisplay()).isFalse();
        assertThat(category.metadata().displayMegaMenu()).isFalse();
        assertThat(category.metadata().applyClpRedirect()).isFalse();
    }

    @Test
    void children_keepOnlyValidAndVisible_unlessMarked404_andCarryNoBreadcrumb() {
        CategoryTreeResponse root = node("root", "Root", true, true, null, null, null, null, null, "home", List.of(
                node("keep", "Kept", true, true, null, null, null, null, "root", "ignored", List.of()),
                node("hidden", "Hidden", true, false, null, null, null, null, "root", null, List.of()),
                node("invalid", "Invalid", false, true, null, null, null, null, "root", null, List.of()),
                node("missing", "Missing", null, null, 404, null, null, null, "root", null, List.of())));
        java.util.List<CategoryTreeResponse> withNull = new java.util.ArrayList<>(root.children());
        withNull.add(null);

        CategoryData category = mapper.toCategory(node("root", "Root", true, true, null, null, null, null, null,
                "home", withNull));

        assertThat(category.children()).extracting(CategoryData::id).containsExactly("KEEP", "MISSING");
        assertThat(category.children()).allSatisfy(child -> assertThat(child.breadcrumb()).isNull());
        assertThat(category.children().getFirst().ancestry()).containsExactly("root");
    }

    private static CategoryTreeResponse node(String id, String title, Boolean valid, Boolean visible, Integer status,
                                             String gggp, String ggp, String gp, String parent, String breadCrumb,
                                             List<CategoryTreeResponse> children) {
        return new CategoryTreeResponse(id, title, null, parent, null, "/tienda/" + id, "CLP", 1, id,
                null, null, null, null, null, null, gp, null, null, ggp, gggp, null, breadCrumb, null,
                children, visible, valid, status);
    }
}
