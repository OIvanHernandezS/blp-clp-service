package com.liverpool.ms_clp_blp.landing.adapters.categoryindexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.liverpool.ms_clp_blp.clients.categoryindexer.dto.CategoryTreeResponse;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.CategoryMetadata;

/**
 * Maps the indexer's category tree onto {@link CategoryData}, rule for rule with digital_bff's
 * {@code mapCategoryTransform} (Rule 19).
 *
 * <ul>
 *   <li>Ids are upper-cased; {@code name} and {@code displayName} are both the title.</li>
 *   <li>{@code ancestry} is the non-empty ancestors outermost first: {@code gggp, ggp, gp, parent}.</li>
 *   <li>A child is kept only when it is both {@code valid} and {@code visible} — unless the indexer
 *       marks it {@code status: 404}, which the legacy mapper let through.</li>
 *   <li>Absent metadata takes the legacy defaults ({@code isBrand=false}, {@code imageUrl=""},
 *       {@code groupType="Not Specified"}, {@code rootIndex=0}, …).</li>
 *   <li>Only the root carries {@code breadcrumb} ({@code ""} when the indexer has none).</li>
 * </ul>
 *
 * <p>Pure and stateless — no Spring, no I/O.</p>
 */
public class CategoryTreeMapper {

    private static final String DEFAULT_GROUP_TYPE = "Not Specified";
    private static final String EMPTY = "";
    private static final int STATUS_NOT_FOUND = 404;

    /**
     * @param root the indexer's answer for the page's category
     * @return the category with its visible children and breadcrumb
     */
    public CategoryData toCategory(CategoryTreeResponse root) {
        return map(root, Objects.requireNonNullElse(root.breadCrumb(), EMPTY));
    }

    private CategoryData map(CategoryTreeResponse node, String breadcrumb) {
        String id = upper(node.id());
        return new CategoryData(
                id,
                node.title(),
                node.title(),
                node.viewUrl(),
                false,
                ancestry(node),
                metadata(node, id),
                children(node),
                breadcrumb,
                null);
    }

    private List<CategoryData> children(CategoryTreeResponse node) {
        if (node.children() == null) {
            return List.of();
        }
        List<CategoryData> children = new ArrayList<>(node.children().size());
        for (CategoryTreeResponse child : node.children()) {
            if (child != null && keeps(child)) {
                children.add(map(child, null));
            }
        }
        return children;
    }

    /** The legacy child filter: {@code status === 404 || (valid && visible)}. */
    private static boolean keeps(CategoryTreeResponse child) {
        boolean notFound = child.status() != null && child.status() == STATUS_NOT_FOUND;
        return notFound || (Boolean.TRUE.equals(child.valid()) && Boolean.TRUE.equals(child.visible()));
    }

    private static List<String> ancestry(CategoryTreeResponse node) {
        List<String> ancestry = new ArrayList<>(4);
        for (String ancestor : new String[] {node.gggp(), node.ggp(), node.gp(), node.parent()}) {
            if (ancestor != null && !ancestor.isEmpty()) {
                ancestry.add(ancestor);
            }
        }
        return ancestry;
    }

    private static CategoryMetadata metadata(CategoryTreeResponse node, String upperId) {
        return new CategoryMetadata(
                node.level(),
                node.parent(),
                node.title(),
                Boolean.TRUE.equals(node.isBrand()),
                node.viewUrl(),
                Objects.requireNonNullElse(node.imageUrl(), EMPTY),
                node.showpath(),
                node.ancestors(),
                Objects.requireNonNullElse(node.groupType(), DEFAULT_GROUP_TYPE),
                node.rootIndex() == null ? 0 : node.rootIndex(),
                upperId,
                node.parentTitle(),
                node.parentShowpath(),
                node.gp(),
                node.gpTitle(),
                node.gpShowpath(),
                Boolean.TRUE.equals(node.rootDisplay()),
                Objects.requireNonNullElse(node.categoryImage(), EMPTY),
                Boolean.TRUE.equals(node.displayMegaMenu()),
                Boolean.TRUE.equals(node.applyClpRedirect()));
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
