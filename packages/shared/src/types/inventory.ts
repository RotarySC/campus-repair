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
