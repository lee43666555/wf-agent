package com.libo.wfagent.ecl;

import com.libo.wfagent.domain.model.EclParam;
import com.libo.wfagent.domain.repository.EclRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 减值计算器。所有金额都在这里算，模型只负责解释结果。
 * <p>
 * 两期差额做量价拆分，把变化拆成三个互不重叠的部分：
 * <pre>
 * ECL = EAD × PD × LGD
 * 规模效应   = (EAD₁ - EAD₀) × PD₀ × LGD₀
 * 风险效应   = EAD₁ × (PD₁ - PD₀) × LGD₀
 * 损失率效应 = EAD₁ × PD₁ × (LGD₁ - LGD₀)
 * 三项之和恰好等于 ECL₁ - ECL₀
 * </pre>
 * 这样业务方问「为什么这个月多提了」时，答案能落到具体是规模涨了还是风险恶化了，
 * 而不是一句「模型跑出来就是这个数」。
 */
@Component
public class EclCalculator {

    private final EclRepository eclRepository;

    public EclCalculator(EclRepository eclRepository) {
        this.eclRepository = eclRepository;
    }

    public record PeriodSummary(String period, double totalEcl, double totalEad,
                                long customerCount, Map<String, Double> eclByStage) {
    }

    public record SegmentDelta(String stage, String segment,
                               double eclA, double eclB, double delta,
                               double scaleEffect, double riskEffect, double lossRateEffect) {
    }

    public record Comparison(String periodA, String periodB,
                             double totalA, double totalB, double totalDelta,
                             List<SegmentDelta> segments, List<String> topDrivers) {
    }

    public PeriodSummary summary(String period) {
        List<EclParam> params = eclRepository.findByPeriod(period);
        double totalEcl = 0.0;
        double totalEad = 0.0;
        long customers = 0L;
        Map<String, Double> byStage = new TreeMap<>();
        for (EclParam param : params) {
            totalEcl += param.ecl();
            totalEad += param.ead();
            customers += param.customerCount();
            byStage.merge(param.stage(), param.ecl(), Double::sum);
        }
        return new PeriodSummary(period, round2(totalEcl), round2(totalEad), customers, round2Map(byStage));
    }

    public Comparison compare(String periodA, String periodB) {
        Map<String, EclParam> indexA = index(eclRepository.findByPeriod(periodA));
        Map<String, EclParam> indexB = index(eclRepository.findByPeriod(periodB));

        // 并集遍历：只出现在 A 的段（消失）与只出现在 B 的段（新增）都不能静默丢弃。
        // 旧实现只遍历 A 的 key，B 独有段会被跳过——totalB 少算、分客群差额加不回总差额，而且不报错。
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(indexA.keySet());
        keys.addAll(indexB.keySet());

        List<SegmentDelta> rows = new ArrayList<>();
        for (String key : keys) {
            EclParam a = indexA.get(key);
            EclParam b = indexB.get(key);
            // 缺的一侧按「同 PD/LGD、零 EAD」补成零敞口：整段差额全部归为规模效应（新增/消失）。
            EclParam base = a != null ? a : b;
            EclParam aa = a != null ? a : zeroExposure(base);
            EclParam bb = b != null ? b : zeroExposure(base);

            double eclA = aa.ecl();
            double eclB = bb.ecl();
            double scale = (bb.ead() - aa.ead()) * aa.pd() * aa.lgd();
            double risk = bb.ead() * (bb.pd() - aa.pd()) * aa.lgd();
            double lossRate = bb.ead() * bb.pd() * (bb.lgd() - aa.lgd());
            rows.add(new SegmentDelta(aa.stage(), aa.segment(),
                    round2(eclA), round2(eclB), round2(eclB - eclA),
                    round2(scale), round2(risk), round2(lossRate)));
        }

        rows.sort(Comparator.comparingDouble((SegmentDelta d) -> Math.abs(d.delta())).reversed());

        double totalA = rows.stream().mapToDouble(SegmentDelta::eclA).sum();
        double totalB = rows.stream().mapToDouble(SegmentDelta::eclB).sum();

        List<String> drivers = rows.stream()
                .filter(d -> Math.abs(d.delta()) >= 1.0)
                .limit(3)
                .map(this::describe)
                .toList();

        return new Comparison(periodA, periodB, round2(totalA), round2(totalB),
                round2(totalB - totalA), rows, drivers);
    }

    private String describe(SegmentDelta d) {
        return String.format("%s-%s：%s，其中规模 %s、风险 %s、损失率 %s",
                d.stage(), d.segment(), signed(d.delta()),
                signed(d.scaleEffect()), signed(d.riskEffect()), signed(d.lossRateEffect()));
    }

    private String signed(double value) {
        return String.format("%s%,.0f 元", value >= 0 ? "+" : "", value);
    }

    private Map<String, EclParam> index(List<EclParam> params) {
        Map<String, EclParam> map = new LinkedHashMap<>();
        for (EclParam param : params) {
            map.put(param.stage() + "|" + param.segment(), param);
        }
        return map;
    }

    /**
     * 缺失一侧的占位参数：保留 PD/LGD 用于归因，敞口与 ECL 记 0。
     * 这样「新增段」的整段 ECL 归为规模效应、「消失段」的整段 ECL 归为负规模效应，
     * 三项效应之和依然等于该段差额，恒等式不被破坏。
     */
    private EclParam zeroExposure(EclParam base) {
        return new EclParam(base.period(), base.stage(), base.segment(),
                base.pd(), base.lgd(), 0.0, 0.0, 0, base.modelVersion());
    }

    private Map<String, Double> round2Map(Map<String, Double> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, round2(value)));
        return result;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
