package com.axelor.apps.supplychain.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.joda.time.LocalDate;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.IProduct;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductVariant;
import com.axelor.apps.base.db.TrackingNumber;
import com.axelor.apps.base.service.ProductVariantService;
import com.axelor.apps.supplychain.db.IStockMove;
import com.axelor.apps.supplychain.db.Inventory;
import com.axelor.apps.supplychain.db.InventoryLine;
import com.axelor.apps.supplychain.db.Location;
import com.axelor.apps.supplychain.db.LocationLine;
import com.axelor.apps.supplychain.db.StockMove;
import com.axelor.apps.supplychain.db.StockMoveLine;
import com.axelor.apps.tool.file.CsvTool;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class InventoryService {

	@Inject
	private StockMoveService stockMoveService;

	@Inject
	private StockMoveLineService stockMoveLineService;

	@Inject
	private ProductVariantService productVariantService;


	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void importFile(String filePath, char separator, Inventory inventory) throws IOException, AxelorException {

		List<InventoryLine> inventoryLineList = inventory.getInventoryLineList();

		List<String[]> data = this.getDatas(filePath, separator);

		HashMap<String,InventoryLine> inventoryLineMap = this.getInventoryLines(inventory);

		for (String[] line : data) {
			if (line.length < 6)
				throw new AxelorException("An error occurred while importing the file data. Please contact your application administrator to check Traceback logs.", IException.CONFIGURATION_ERROR);

			String code = line[1].replace("\"", "");
			String productVariant = line[2].replace("\"", "");
			String trackingNumberSeq = line[3].replace("\"", "");

			Integer realQty = 0;
			try {
				realQty = Integer.valueOf(line[6].replace("\"", ""));
			}catch(NumberFormatException e) {
				throw new AxelorException("An error occurred while importing the file data. Please contact your application administrator to check Traceback logs.", IException.CONFIGURATION_ERROR);
			}

			String description = line[7].replace("\"", "");

			if (inventoryLineMap.containsKey(code)) {
				inventoryLineMap.get(code).setRealQty(new BigDecimal(realQty));
				inventoryLineMap.get(code).setDescription(description);
			}
			else {
				Integer currentQty = 0;
				try {
					currentQty = Integer.valueOf(line[4].replace("\"", ""));
				}catch(NumberFormatException e) {
					throw new AxelorException("An error occurred while importing the file data. Please contact your application administrator to check Traceback logs.", IException.CONFIGURATION_ERROR);
				}

				InventoryLine inventoryLine = new InventoryLine();
				Product product = Product.findByCode(code);
				if (product == null || product.getApplicationTypeSelect() != IProduct.PRODUCT_TYPE || !product.getProductTypeSelect().equals(IProduct.STORABLE))
					throw new AxelorException("An error occurred while importing the file data, product not found with code : "+code, IException.CONFIGURATION_ERROR);
				inventoryLine.setProduct(product);
				inventoryLine.setInventory(inventory);
				inventoryLine.setCurrentQty(new BigDecimal(currentQty));
				inventoryLine.setRealQty(new BigDecimal(realQty));
				inventoryLine.setDescription(description);
				inventoryLine.setProductVariant(ProductVariant.findByName(productVariant)); // TODO remplacer par un split sur les paramètres depuis le nom
				inventoryLine.setTrackingNumber(this.getTrackingNumber(trackingNumberSeq));
				inventoryLineList.add(inventoryLine);
			}
		}
		inventory.setInventoryLineList(inventoryLineList);

		inventory.save();
	}


	public List<String[]> getDatas(String filePath, char separator) throws AxelorException  {

		List<String[]> data = null;
		try {
			data = CsvTool.cSVFileReader(filePath, separator);
		} catch(Exception e) {
			throw new AxelorException("There is currently no such file in the specified folder or the folder may not exists.", IException.CONFIGURATION_ERROR);
		}

		if (data == null || data.isEmpty())  {
			throw new AxelorException("An error occurred while importing the file data. Please contact your application administrator to check Traceback logs.", IException.CONFIGURATION_ERROR);
		}

		return data;


	}


	public HashMap<String,InventoryLine> getInventoryLines(Inventory inventory)  {
		HashMap<String,InventoryLine> inventoryLineMap = new HashMap<String,InventoryLine>();

		for (InventoryLine line : inventory.getInventoryLineList()) {
			String key = "";
			if(line.getProduct() != null)  {
				key += line.getProduct().getCode();
			}	
			if(line.getProductVariant() != null)  {
				key += line.getProductVariant().getName();
			}
			if(line.getTrackingNumber() != null)  {
				key += line.getTrackingNumber().getTrackingNumberSeq();
			}

			inventoryLineMap.put(key, line);
		}

		return inventoryLineMap;
	}


	public TrackingNumber getTrackingNumber(String sequence)  {

		if(sequence != null && !sequence.isEmpty())  {
			return TrackingNumber.all().filter("self.trackingNumberSeq = ?1", sequence).fetchOne();
		}

		return null;

	}

	public void generateStockMove(Inventory inventory) throws AxelorException {

		Location toLocation = inventory.getLocation();
		Company company = toLocation.getCompany();

		if (company == null) {
			throw new AxelorException(String.format("Société manquante pour l'entrepot {}", toLocation.getName()), IException.CONFIGURATION_ERROR);
		}

		String inventorySeq = inventory.getInventorySeq();

		StockMove stockMove = this.createStockMoveHeader(inventory, company, toLocation, inventory.getDateT().toLocalDate(), inventorySeq);

		for (InventoryLine inventoryLine : inventory.getInventoryLineList()) {
			BigDecimal currentQty = inventoryLine.getCurrentQty();
			BigDecimal realQty = inventoryLine.getRealQty();
			Product product = inventoryLine.getProduct();

			if (currentQty.compareTo(realQty) != 0) {
				BigDecimal diff = realQty.subtract(currentQty);

				StockMoveLine stockMoveLine = stockMoveLineService.createStockMoveLine(product, diff, product.getUnit(), null, stockMove, inventoryLine.getProductVariant(), 0);
				if (stockMoveLine == null)  {
					throw new AxelorException("Produit incorrect dans la ligne de l'inventaire "+inventorySeq, IException.CONFIGURATION_ERROR);
				}

				if(stockMove.getStockMoveLineList() == null) {
					stockMove.setStockMoveLineList(new ArrayList<StockMoveLine>());
				}
				stockMove.getStockMoveLineList().add(stockMoveLine);
			}
		}
		if (stockMove.getStockMoveLineList() != null) {
			stockMoveService.validate(stockMove);
		}
	}

	public StockMove createStockMoveHeader(Inventory inventory, Company company, Location toLocation, LocalDate inventoryDate, String name) throws AxelorException  {

		StockMove stockMove = stockMoveService.createStockMove(null, company, null, company.getInventoryVirtualLocation(), toLocation, inventoryDate, inventoryDate);
		stockMove.setTypeSelect(IStockMove.INTERNAL);
		stockMove.setName(name);

		return stockMove;
	}


	public InventoryLine createInventoryLine(Product product, BigDecimal currentQty, Inventory inventory, TrackingNumber trackingNumber, ProductVariant productVariant) {

		InventoryLine inventoryLine = new InventoryLine();
		inventoryLine.setProduct(product);
		inventoryLine.setCurrentQty(currentQty);
		inventoryLine.setInventory(inventory);
		inventoryLine.setTrackingNumber(trackingNumber);
		inventoryLine.setProductVariant(productVariant);

		return inventoryLine;
	}

	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public List<InventoryLine> fillInventoryLineList(Inventory inventory) {

		Location location = inventory.getLocation();
		String codeCategory = null;
		String codeFamily = null;

		if(inventory.getProductCategory() != null)
			codeCategory = inventory.getProductCategory().getCode();
		if(inventory.getProductFamily() != null)
			codeFamily = inventory.getProductFamily().getCode();

		if(location != null && location.getLocationLineList() != null) {
			List<InventoryLine> inventoryLineList = new ArrayList<InventoryLine>();

			for(LocationLine locationLine : location.getLocationLineList()) {

				Product product = locationLine.getProduct();
				if(product != null) {
					if((inventory.getProductCategory() == null && inventory.getProductFamily() == null)
						|| (product.getProductCategory() != null && product.getProductCategory().getCode() != null && codeCategory != null && product.getProductCategory().getCode().equals(codeCategory))
						|| (product.getProductFamily() != null && product.getProductFamily().getCode() != null && codeFamily != null && product.getProductFamily().getCode().equals(codeFamily))) {
						
						if(codeCategory != null && codeFamily != null && product.getProductCategory() != null && product.getProductFamily() != null && product.getProductCategory().getCode() != null && product.getProductFamily().getCode() != null 
							&& ((!product.getProductCategory().getCode().equals(codeCategory)) || (!product.getProductFamily().getCode().equals(codeFamily))))
							continue;
						if(inventory.getExcludeOutOfStock()) {
							if(locationLine.getCurrentQty().compareTo(BigDecimal.ZERO) > 0) {
								if(!inventory.getIncludeObsolete()) {
									if((product.getEndDate() == null) || (inventory.getDateT() != null && inventory.getDateT().toLocalDate().isBefore(product.getEndDate())))
										inventoryLineList.add(createInventoryLine(product, locationLine.getCurrentQty(), inventory, locationLine.getTrackingNumber(), locationLine.getProductVariant()));
								}
								else 
									inventoryLineList.add(createInventoryLine(product, locationLine.getCurrentQty(), inventory, locationLine.getTrackingNumber(), locationLine.getProductVariant()));
							}
						}
						else {
							if(!inventory.getIncludeObsolete()) {
								if((product.getEndDate() == null) || (inventory.getDateT() != null && inventory.getDateT().toLocalDate().isBefore(product.getEndDate())))
									inventoryLineList.add(createInventoryLine(product, locationLine.getCurrentQty(), inventory, locationLine.getTrackingNumber(), locationLine.getProductVariant()));
							}
							else
								inventoryLineList.add(createInventoryLine(product, locationLine.getCurrentQty(), inventory, locationLine.getTrackingNumber(), locationLine.getProductVariant()));
						}
					}
				}
			}
			inventory.setInventoryLineList(inventoryLineList);
			inventory.save();
			return inventory.getInventoryLineList();
		}
		return null;
	}
}
