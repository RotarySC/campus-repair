export interface SparePartVO {
  id: number;
  partCode: string;
  partName: string;
  categoryType: string;
  specification?: string;
  unit: string;
  guidePrice: number;
  stockQuantity: number;
  safetyStockThreshold: number;
  status: number;
  isLowStock: boolean;
  createdAt?: string;
}

export interface SparePartCreateUpdateRequest {
  id?: number;
  partCode: string;
  partName: string;
  categoryType: string;
  specification?: string;
  unit: string;
  guidePrice: number;
  safetyStockThreshold: number;
  status: number;
}

export interface SparePartInboundRequest {
  partId: number;
  quantity: number;
  unitPrice: number;
  batchDate?: string;
  remark?: string;
}

export interface SparePartInboundVO {
  id: number;
  inboundNo: string;
  partId: number;
  partName: string;
  batchDate: string;
  quantity: number;
  unitPrice: number;
  totalAmount: number;
  operatorId: number;
  operatorName: string;
  remark?: string;
  createdAt: string;
}

export interface InventorySummaryVO {
  totalPartCount: number;
  lowStockCount: number;
  normalCount: number;
  disabledCount: number;
}

export interface PurchaseAdviceItemVO {
  partCode: string;
  partName: string;
  categoryType: string;
  specification?: string;
  unit: string;
  stockQuantity: number;
  safetyStockThreshold: number;
  shortageQuantity: number;
  recommendedPurchaseQuantity: number;
  guidePrice: number;
  estimatedTotalAmount: number;
}

export interface MaterialConsumeItem {
  partId: number;
  consumeQuantity: number;
}

export interface WorkOrderFinishRequest {
  repairSummary: string;
  repairImages: string[];
  materialConsumptions?: MaterialConsumeItem[];
}

export interface RepairMaterialConsumeVO {
  id: number;
  orderId: number;
  orderNo: string;
  workerId: number;
  partId: number;
  partName: string;
  consumeQuantity: number;
  unitPrice: number;
  totalCost: number;
  createdAt: string;
}

export const SparePartCategoryOptions = [
  '水暖管件',
  '电工电气',
  '门窗五金',
  '空调配件',
  '其他综合'
];

