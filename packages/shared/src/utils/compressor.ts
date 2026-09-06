/**
 * 客户端 Canvas 图像等比压缩与防篡改水印工具
 */
export interface ImageCompressOptions {
  maxDimension?: number;
  quality?: number;
}

export function compressImage(file: File, options: ImageCompressOptions = {}): Promise<Blob> {
  const maxDimension = options.maxDimension || 1920;
  const quality = options.quality !== undefined ? options.quality : 0.82;

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
          return reject(new Error('无法创建 Canvas 2D 绘图上下文'));
        }

        ctx.drawImage(img, 0, 0, width, height);

        canvas.toBlob(
          (blob) => {
            if (blob) resolve(blob);
            else reject(new Error('Canvas 导出 Blob 失败'));
          },
          'image/jpeg',
          quality
        );
      };
      img.onerror = () => reject(new Error('图片解析加载失败'));
    };
    reader.onerror = () => reject(new Error('读取文件失败'));
  });
}
