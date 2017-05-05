/******************************************************************************
 * Copyright © 2017 XIN Community                                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package xin.proxies.litecoin;

import org.json.simple.JSONObject;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;


public interface LiteCoinService {

    @GET("api/v1/address/balance/{address}")
    public Call<JSONObject> getLiteCoinBalance(@Path("address") String accountId);
}
