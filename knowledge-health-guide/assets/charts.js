(function() {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();

  // --- Chart: 文档引用热度 ---
  var el1 = document.getElementById('chart-doc-heat');
  if (el1) {
    var chart1 = echarts.init(el1, null, { renderer: 'svg' });
    chart1.setOption({
      animation: false,
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, appendToBody: true },
      grid: { left: '18%', right: '8%', top: 20, bottom: 30 },
      xAxis: {
        type: 'value',
        name: '引用次数',
        nameTextStyle: { color: muted, fontSize: 12 },
        axisLine: { lineStyle: { color: rule } },
        axisLabel: { color: muted },
        splitLine: { lineStyle: { color: rule, type: 'dashed' } }
      },
      yAxis: {
        type: 'category',
        data: ['resume', 'LLM Wiki 技术调研', '贾志远的简历', 'team-handbook', 'llm-wiki-research', '初始文档'],
        axisLine: { lineStyle: { color: rule } },
        axisLabel: { color: ink, fontSize: 12 }
      },
      series: [{
        type: 'bar',
        data: [
          { value: 1, itemStyle: { color: accent } },
          { value: 1, itemStyle: { color: accent } },
          { value: 0, itemStyle: { color: muted } },
          { value: 0, itemStyle: { color: muted } },
          { value: 0, itemStyle: { color: muted } },
          { value: 0, itemStyle: { color: muted } }
        ],
        barWidth: '50%',
        label: { show: true, position: 'right', color: ink, fontSize: 12 }
      }]
    });
    window.addEventListener('resize', function() { chart1.resize(); });
  }

  // --- Chart: 僵尸文档占比 ---
  var el2 = document.getElementById('chart-zombie-pie');
  if (el2) {
    var chart2 = echarts.init(el2, null, { renderer: 'svg' });
    chart2.setOption({
      animation: false,
      tooltip: { trigger: 'item', appendToBody: true },
      legend: { bottom: 10, textStyle: { color: muted, fontSize: 12 } },
      series: [{
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['50%', '45%'],
        data: [
          { value: 2, name: '活跃文档', itemStyle: { color: accent } },
          { value: 4, name: '僵尸文档', itemStyle: { color: accent2 } }
        ],
        label: { color: ink, fontSize: 13 },
        labelLine: { lineStyle: { color: rule } }
      }]
    });
    window.addEventListener('resize', function() { chart2.resize(); });
  }
})();
