/**
 * 客户端 Canvas 防伪防篡改时间水印生成器
 * 在现场完工照片右下角压制包含工单编号、维修师傅姓名、当前时间戳的半透明防伪底卡与文字
 */

export interface WatermarkOptions {
  orderNo: string;
  workerName: string;
  timestamp?: string; // 默认当前时间 yyyy-MM-dd HH:mm:ss
  maxDimension?: number;
  quality?: number;
}

export function formatDateTime(date: Date = new Date()): string {
  const pad = (n: number) => n.toString().padStart(2, '0');
  const year = date.getFullYear();
  const month = pad(date.getMonth() + 1);
  const day = pad(date.getDate());
  const hours = pad(date.getHours());
  const minutes = pad(date.getMinutes());
  const seconds = pad(date.getSeconds());
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
}

/**
 * 将 File 或 Blob 压入 Canvas，并在右下角绘制半透明圆角底卡与防伪防篡改信息
 */
export function addWatermarkToImage(file: File | Blob, options: WatermarkOptions): Promise<Blob> {
  const maxDimension = options.maxDimension || 1920;
  const quality = options.quality !== undefined ? options.quality : 0.85;
  const timeText = options.timestamp || formatDateTime();

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.readAsDataURL(file);
    reader.onload = (e) => {
      const img = new Image();
      img.src = e.target?.result as string;
      img.onload = () => {
        let { width, height } = img;
        if (width > maxDimension || height > maxDimension) {
          if (width > height) {
            height = Math.round((height * maxDimension) / width);
            width = maxDimension;
          } else {
            width = Math.round((width * maxDimension) / height);
            height = maxDimension;
          }
        }

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          return reject(new Error('无法创建 Canvas 2D 上下文'));
        }

        // 1. 绘制原图（缩放至目标尺寸）
        ctx.drawImage(img, 0, 0, width, height);

        // 2. 计算动态水印文字大小与卡片尺寸
        // 基准：长边 1000px 时字体 16px
        const baseDimension = Math.max(width, height);
        const fontSize = Math.max(14, Math.round(baseDimension * 0.018));
        const lineHeight = Math.round(fontSize * 1.45);
        const padding = Math.round(fontSize * 0.9);
        const margin = Math.round(fontSize * 1.2);

        const line1 = `工单单号: ${options.orderNo}`;
        const line2 = `作业师傅: ${options.workerName}`;
        const line3 = `完工时间: ${timeText}`;

        ctx.font = `600 ${fontSize}px sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto`;

        const m1 = ctx.measureText(line1).width;
        const m2 = ctx.measureText(line2).width;
        const m3 = ctx.measureText(line3).width;
        const maxTextWidth = Math.max(m1, m2, m3);

        const cardWidth = maxTextWidth + padding * 2;
        const cardHeight = lineHeight * 3 + padding * 2;

        const cardX = width - cardWidth - margin;
        const cardY = height - cardHeight - margin;

        // 3. 绘制半透明黑色圆角背景卡片
        ctx.save();
        ctx.fillStyle = 'rgba(0, 0, 0, 0.58)';
        const radius = Math.round(fontSize * 0.4);

        ctx.beginPath();
        ctx.moveTo(cardX + radius, cardY);
        ctx.lineTo(cardX + cardWidth - radius, cardY);
        ctx.quadraticCurveTo(cardX + cardWidth, cardY, cardX + cardWidth, cardY + radius);
        ctx.lineTo(cardX + cardWidth, cardY + cardHeight - radius);
        ctx.quadraticCurveTo(cardX + cardWidth, cardY + cardHeight, cardX + cardWidth - radius, cardY + cardHeight);
        ctx.lineTo(cardX + radius, cardY + cardHeight);
        ctx.quadraticCurveTo(cardX, cardY + cardHeight, cardX, cardY + cardHeight - radius);
        ctx.lineTo(cardX, cardY + radius);
        ctx.quadraticCurveTo(cardX, cardY, cardX + radius, cardY);
        ctx.closePath();
        ctx.fill();

        // 4. 绘制文字 (高对比度白字 + 轻微阴影)
        ctx.shadowColor = 'rgba(0, 0, 0, 0.7)';
        ctx.shadowBlur = 4;
        ctx.shadowOffsetX = 1;
        ctx.shadowOffsetY = 1;
        ctx.fillStyle = '#FFFFFF';
        ctx.textBaseline = 'top';

        const textStartX = cardX + padding;
        const textStartY = cardY + padding;

        ctx.fillText(line1, textStartX, textStartY);
        ctx.fillText(line2, textStartX, textStartY + lineHeight);
        ctx.fillText(line3, textStartX, textStartY + lineHeight * 2);

        ctx.restore();

        // 5. 导出处理后的图片 Blob
        canvas.toBlob(
          (blob) => {
            if (blob) resolve(blob);
            else reject(new Error('Canvas 导出水印图片失败'));
          },
          'image/jpeg',
          quality
        );
      };
      img.onerror = () => reject(new Error('图片加载解析失败'));
    };
    reader.onerror = () => reject(new Error('读取待加水印图片文件失败'));
  });
}
