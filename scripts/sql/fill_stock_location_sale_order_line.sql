UPDATE sale_sale_order_line SET stock_location=subquery.stock_location FROM (
SELECT so_line.id, so.stock_location
FROM sale_sale_order_line AS so_line LEFT JOIN sale_sale_order AS so
ON so_line.sale_order = so.id
WHERE so_line.type_select = 0 AND so_line.stock_location IS NULL AND so.stock_location IS NOT NULL)
AS subquery
WHERE sale_sale_order_line.id = subquery.id;