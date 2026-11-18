package com.libo.wfagent.ecl;

import com.libo.wfagent.domain.model.EclParam;
import com.libo.wfagent.domain.repository.EclRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 减值归因的核心断言：量价拆分出来的三块，加起来必须正好等于总差额。
 * <p>
 * 这条如果破了，「可复算」就不成立——业务方按三块效应加回去对不上总数，
 * 结论直接失去审计价值。
 */
class EclCalculatorTest {

    private EclParam param(String period, String stage, String segment,
                           double pd, double lgd, double ead, int customers) {
        return new EclParam(period, stage, segment, pd, lgd, ead, ead, customers, "MDL_TEST");
    }

    @Test
    @DisplayName("量价拆分三项之和等于总差额")
    void decompositionSumsToDelta() {
        EclParam a = param("202608", "STAGE2", "普通客群", 0.142, 0.63, 47_000_000, 4300);
        EclParam b = param("202609", "STAGE2", "普通客群", 0.158, 0.63, 52_000_000, 4700);

        EclRepository repository = mock(EclRepository.class);
        when(repository.findByPeriod("202608")).thenReturn(java.util.List.of(a));
        when(repository.findByPeriod("202609")).thenReturn(java.util.List.of(b));

        EclCalculator.Comparison comparison = new EclCalculator(repository).compare("202608", "202609");

        assertThat(comparison.segments()).hasSize(1);
        EclCalculator.SegmentDelta row = comparison.segments().get(0);

        // 手工可复算：47,000,000 × 0.142 × 0.63 = 4,204,620；52,000,000 × 0.158 × 0.63 = 5,176,080
        assertThat(row.eclA()).isCloseTo(4_204_620.0, within(0.01));
        assertThat(row.eclB()).isCloseTo(5_176_080.0, within(0.01));
        assertThat(row.delta()).isCloseTo(971_460.0, within(0.01));

        // 规模效应 5,000,000 × 0.142 × 0.63 = 447,300；风险效应 52,000,000 × 0.016 × 0.63 = 524,160
        assertThat(row.scaleEffect()).isCloseTo(447_300.0, within(0.01));
        assertThat(row.riskEffect()).isCloseTo(524_160.0, within(0.01));
        assertThat(row.lossRateEffect()).isCloseTo(0.0, within(0.01));

        assertThat(row.scaleEffect() + row.riskEffect() + row.lossRateEffect())
                .isCloseTo(row.delta(), within(0.01));
    }

    @Test
    @DisplayName("损失率变化时三项依然闭合")
    void decompositionClosesWhenLgdChanges() {
        EclParam a = param("202608", "STAGE3", "普通客群", 0.51, 0.68, 13_000_000, 860);
        EclParam b = param("202609", "STAGE3", "普通客群", 0.53, 0.72, 14_200_000, 930);

        EclRepository repository = mock(EclRepository.class);
        when(repository.findByPeriod("202608")).thenReturn(java.util.List.of(a));
        when(repository.findByPeriod("202609")).thenReturn(java.util.List.of(b));

        EclCalculator.SegmentDelta row = new EclCalculator(repository)
                .compare("202608", "202609").segments().get(0);

        assertThat(row.lossRateEffect()).isGreaterThan(0.0);
        assertThat(row.scaleEffect() + row.riskEffect() + row.lossRateEffect())
                .isCloseTo(row.delta(), within(0.01));
    }

    @Test
    @DisplayName("两期总额与差额一致")
    void totalsAreConsistent() {
        EclParam a1 = param("202608", "STAGE1", "优质客群", 0.0120, 0.45, 86_000_000, 12_400);
        EclParam a2 = param("202608", "STAGE1", "普通客群", 0.0350, 0.52, 152_000_000, 21_800);
        EclParam b1 = param("202609", "STAGE1", "优质客群", 0.0135, 0.45, 84_000_000, 12_100);
        EclParam b2 = param("202609", "STAGE1", "普通客群", 0.0410, 0.52, 148_000_000, 21_200);

        EclRepository repository = mock(EclRepository.class);
        when(repository.findByPeriod("202608")).thenReturn(java.util.List.of(a1, a2));
        when(repository.findByPeriod("202609")).thenReturn(java.util.List.of(b1, b2));

        EclCalculator.Comparison comparison = new EclCalculator(repository).compare("202608", "202609");

        assertThat(comparison.totalA()).isCloseTo(3_230_800.0, within(0.01));
        assertThat(comparison.totalB()).isCloseTo(3_665_660.0, within(0.01));
        assertThat(comparison.totalDelta()).isCloseTo(comparison.totalB() - comparison.totalA(), within(0.01));

        double sumOfSegmentDeltas = comparison.segments().stream()
                .mapToDouble(EclCalculator.SegmentDelta::delta).sum();
        assertThat(sumOfSegmentDeltas).isCloseTo(comparison.totalDelta(), within(0.01));
    }

    @Test
    @DisplayName("新增段与消失段不能被静默丢弃，且差额仍闭合")
    void handlesSegmentsThatAppearOrDisappear() {
        EclParam aKeep = param("202608", "STAGE1", "优质客群", 0.012, 0.45, 86_000_000, 12400);
        EclParam aGone = param("202608", "STAGE3", "优质客群", 0.42, 0.68, 6_000_000, 420);
        EclParam bKeep = param("202609", "STAGE1", "优质客群", 0.0135, 0.45, 84_000_000, 12100);
        EclParam bNew = param("202609", "STAGE3", "普通客群", 0.53, 0.72, 14_200_000, 930);

        EclRepository repository = mock(EclRepository.class);
        when(repository.findByPeriod("202608")).thenReturn(List.of(aKeep, aGone));
        when(repository.findByPeriod("202609")).thenReturn(List.of(bKeep, bNew));

        EclCalculator.Comparison c = new EclCalculator(repository).compare("202608", "202609");

        // 并集三个 key：保留 + 消失 + 新增，一个都不能丢
        assertThat(c.segments()).hasSize(3);

        // 新增段（只在 B）：整段 ECL 归规模效应，风险 / 损失率效应为 0
        EclCalculator.SegmentDelta newSeg = c.segments().stream()
                .filter(s -> s.segment().equals("普通客群")).findFirst().orElseThrow();
        assertThat(newSeg.eclA()).isCloseTo(0.0, within(0.01));
        assertThat(newSeg.eclB()).isCloseTo(14_200_000.0 * 0.53 * 0.72, within(0.01));
        assertThat(newSeg.scaleEffect()).isCloseTo(newSeg.delta(), within(0.01));
        assertThat(newSeg.riskEffect()).isCloseTo(0.0, within(0.01));
        assertThat(newSeg.lossRateEffect()).isCloseTo(0.0, within(0.01));

        // 消失段（只在 A）：整段 ECL 归负规模效应
        EclCalculator.SegmentDelta goneSeg = c.segments().stream()
                .filter(s -> s.stage().equals("STAGE3") && s.segment().equals("优质客群"))
                .findFirst().orElseThrow();
        assertThat(goneSeg.eclA()).isCloseTo(6_000_000.0 * 0.42 * 0.68, within(0.01));
        assertThat(goneSeg.eclB()).isCloseTo(0.0, within(0.01));
        assertThat(goneSeg.scaleEffect()).isCloseTo(goneSeg.delta(), within(0.01));

        // 分客群差额之和仍等于总差额（可复算的第一层不破）
        double sum = c.segments().stream().mapToDouble(EclCalculator.SegmentDelta::delta).sum();
        assertThat(sum).isCloseTo(c.totalDelta(), within(0.01));
    }
}
