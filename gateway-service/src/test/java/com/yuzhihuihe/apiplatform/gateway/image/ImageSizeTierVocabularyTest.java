package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 生图档位词表与"像素尺寸 → 档位"归约（网关 V115 把 {@code 1.5K} 变成一等档位）。
 *
 * <p>这条词表是**四处共同的事实**：网关受理清单、目录下发、费率卡与任务表的 CHECK、
 * 平台控制台的登记词表。它们分散在四个地方，任何一处漏掉 1.5K 都表现为一种具体的
 * 客户故障：
 *
 * <ul>
 *   <li>漏在受理清单 → 客户端能选、提交却被 {@code invalid_image_request} 拒；
 *   <li>漏在归约规则 → 一个 1536 边的合法像素尺寸归约不出档位，同样被拒；
 *   <li>漏在费率卡 CHECK → 这一档没有成本基准，平台无法给它定价；
 *   <li>漏在平台词表 → 别名能出图，却没有任何套餐能登记并上架它。
 * </ul>
 *
 * <p>因此这里钉的是关系而不是常量本身：{@code 1.5K} 必须排在 {@code 1K} 与
 * {@code 2K} **之间**（价格阶梯的比较全靠这个次序），并且每一档的单边上限都必须
 * 归约回它自己——归约与词表一旦分叉，就会出现"目录下发的尺寸提交即被拒"。
 */
class ImageSizeTierVocabularyTest {

    @Test
    void ranksOnePointFiveKBetweenOneKAndTwoK() {
        assertThat(ImageGenerationService.SIZE_TIERS)
                .containsExactly("1K", "1.5K", "2K", "4K");
        assertThat(ImageGenerationService.tierRank("1.5K"))
                .isEqualTo(ImageGenerationService.tierRank("1K") + 1)
                .isEqualTo(ImageGenerationService.tierRank("2K") - 1);
        // 大小写不敏感：客户端把档位写成 1.5k 不该被当成未知档位。
        assertThat(ImageGenerationService.tierRank("1.5k"))
                .isEqualTo(ImageGenerationService.tierRank("1.5K"));
        assertThat(ImageGenerationService.tierRank("3K")).isNegative();
    }

    /**
     * 适配器声明的受理清单不得超出台词表：超出的那一档过不了网关校验，
     * 却会被目录当成可提交项下发。
     */
    @Test
    void theAdapterVocabularyIsThePlatformVocabulary() {
        assertThat(Set.copyOf(ImageGenerationService.SIZE_TIERS))
                .isEqualTo(ImageGenerationAdapter.PLATFORM_SIZE_TIERS);

        ImageGenerationAdapter.ImageSubmissionProfile profile =
                new TianYiYunImageGenerationAdapter(null, null)
                        .submissionProfile("doubao-seedream-5.0-pro");

        assertThat(profile.sizeTiers())
                .as("天翼 pro 的受理清单：1.5K 在内")
                .contains("1K", "1.5K", "2K");
        // 4K 于 2026-10-13 被实测删除：直连厂商逐个档位打真实调用，
        // size:"4K" 被以 HTTP 400 InvalidParameter(param=size) 拒绝
        // （scripts/Probe-UpstreamImageSize.ps1，证据在 test-results/）。
        // 这条断言防的是"有人照着旧文档把 4K 加回受理清单"——那会让目录多出一个
        // supportedSizeTiers 项，客户以为能出、提交必被上游拒。
        assertThat(profile.sizeTiers())
                .as("4K 不受厂商支持，不得回到受理清单")
                .doesNotContain("4K");
        assertThat(profile.documentedSizeTiers())
                .as("厂商文档口径：1.5K 本来就在其中，且与受理清单已收敛")
                .containsExactlyInAnyOrderElementsOf(profile.sizeTiers());
        assertThat(ImageGenerationAdapter.PLATFORM_SIZE_TIERS)
                .as("受理清单不得超出台词表（超出的档位过不了网关校验）")
                .containsAll(profile.sizeTiers())
                .as("平台词表本身可以比某个厂商更宽：4K 仍能被表达，只是没有厂商受理")
                .contains("4K");
    }

    /** 每一档的单边上限必须归约回它自己；档位字符串本身也必须原样通过。 */
    @Test
    void everyTierReducesBackToItself() {
        for (String tier : ImageGenerationService.SIZE_TIERS) {
            int edge = ImageGenerationService.tierMaxEdge(tier);
            assertThat(edge).as("tierMaxEdge(%s)", tier).isPositive();
            assertThat(ImageGenerationService.sizeTier(tier))
                    .as("档位字符串 %s", tier)
                    .isEqualTo(tier);
            assertThat(ImageGenerationService.sizeTier(edge + "x" + edge))
                    .as("%d x %d 必须归约回 %s", edge, edge, tier)
                    .isEqualTo(tier);
        }
        assertThat(ImageGenerationService.tierMaxEdge("3K")).isNegative();
    }

    /**
     * 归约按最长边分档：1024 是 1K、1536 是 1.5K、2048 是 2K，各档的上界+1
     * 落到下一档。这是 mid-tier 的关键边界——1536 是厂商 1.5K 的可渲染边长。
     */
    @Test
    void reducesByTheLongestEdgeAcrossTheMidTierBoundary() {
        assertThat(ImageGenerationService.sizeTier("1024x1024")).isEqualTo("1K");
        assertThat(ImageGenerationService.sizeTier("1024x1025")).isEqualTo("1.5K");
        assertThat(ImageGenerationService.sizeTier("1536x1536")).isEqualTo("1.5K");
        assertThat(ImageGenerationService.sizeTier("1536x864")).isEqualTo("1.5K");
        assertThat(ImageGenerationService.sizeTier("1537x1537")).isEqualTo("2K");
        assertThat(ImageGenerationService.sizeTier("2048x2048")).isEqualTo("2K");
        assertThat(ImageGenerationService.sizeTier("2048x2049")).isEqualTo("4K");
    }

    /** 单边越界与无法解析的取值仍然是 null（fail-closed），1.5K 不改变这两条。 */
    @Test
    void refusesEdgesOutsideThePlatformRangeAndUnparseableSizes() {
        assertThat(ImageGenerationService.sizeTier("63x1536")).isNull();
        assertThat(ImageGenerationService.sizeTier("4097x1536")).isNull();
        assertThat(ImageGenerationService.sizeTier("1.9K")).isNull();
        assertThat(ImageGenerationService.sizeTier("1500x")).isNull();
    }
}
